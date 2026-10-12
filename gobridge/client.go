package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"go.mau.fi/whatsmeow"
	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	waLog "go.mau.fi/whatsmeow/util/log"
	"google.golang.org/protobuf/proto"
)

// RetentionConfig controls how long messages and media are kept.
type RetentionConfig struct {
	MaxMessages  int // Max messages per conversation (0 = unlimited)
	MaxDays      int // Max age in days (0 = unlimited)
	MediaTTLMins int // Minutes before temp media files are deleted
}

// Bridge holds the whatsmeow client, the message DB, and the line to Kotlin.
type Bridge struct {
	client  *whatsmeow.Client
	msgDB   *sql.DB
	dataDir string
	log     waLog.Logger

	mu        sync.RWMutex
	retention RetentionConfig

	// out carries responses and events to Kotlin (stdout); see transport.go.
	out *lineWriter
	// emit delivers one outbound event envelope to Kotlin. NewBridge wires it
	// to the transport; tests swap in a recorder.
	emit func(Envelope)

	connectionStateMu sync.RWMutex
	connectionState   string
	connectionReason  string
	reconnectMu       sync.Mutex
	reconnectNext     time.Time
	reconnectDelay    time.Duration
	reconnectPaused   bool

	groupNameMu sync.Mutex
	groupNames  map[string]cachedGroupName

	calls      callTracker
	reconciler coalescer

	// retryDelay is the first wait before retrying a database write that
	// failed (see applyRevoke).
	retryDelay time.Duration

	// isOnWhatsApp asks WhatsApp which phone numbers it knows (see
	// CheckOnWhatsApp). Nil uses the client; tests answer instead.
	isOnWhatsApp func(ctx context.Context, phones []string) ([]types.IsOnWhatsAppResponse, error)

	// mediaUploader and messageSender reach WhatsApp for media sends and
	// retries (see uploadMedia and sendWithID). Nil uses the client; tests
	// stand in for WhatsApp instead.
	mediaUploader func(ctx context.Context, data []byte, mediaType whatsmeow.MediaType) (whatsmeow.UploadResponse, error)
	messageSender func(ctx context.Context, to types.JID, msg *waE2E.Message, extra ...whatsmeow.SendRequestExtra) (whatsmeow.SendResponse, error)
}

// NewBridge creates a new Bridge instance.
func NewBridge(client *whatsmeow.Client, msgDB *sql.DB, dataDir string, log waLog.Logger) *Bridge {
	b := &Bridge{
		client:          client,
		msgDB:           msgDB,
		dataDir:         dataDir,
		log:             log,
		connectionState: "disconnected",
		retryDelay:      time.Second,
		groupNames:      make(map[string]cachedGroupName),
		retention: RetentionConfig{
			MaxMessages:  50,
			MaxDays:      7,
			MediaTTLMins: 5,
		},
	}
	b.emit = b.writeEvent
	return b
}

// SetRetention updates the retention configuration.
func (b *Bridge) SetRetention(maxMessages, maxDays, mediaTTLMins int) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if maxMessages > 0 {
		b.retention.MaxMessages = maxMessages
	}
	if maxDays > 0 {
		b.retention.MaxDays = maxDays
	}
	if mediaTTLMins > 0 {
		b.retention.MediaTTLMins = mediaTTLMins
	}
}

// StartPruning runs periodic cleanup of old messages and temp media, and
// deletes disappearing messages as their timers run out: at once (they may
// have run out while the bridge was down), then every minute.
func (b *Bridge) StartPruning(ctx context.Context) {
	go func() {
		b.expireMessages(time.Now().Unix())
		ticker := time.NewTicker(5 * time.Minute)
		defer ticker.Stop()
		expiry := time.NewTicker(expiryInterval)
		defer expiry.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-expiry.C:
				b.expireMessages(time.Now().Unix())
			case <-ticker.C:
				b.pruneMessages()
				b.pruneMedia()
			}
		}
	}()
}

// expiryInterval is how often disappeared messages are looked for; one
// lingers at most this long past its time.
const expiryInterval = time.Minute

func (b *Bridge) pruneMessages() {
	b.mu.RLock()
	cfg := b.retention
	b.mu.RUnlock()

	// Prune by age
	if cfg.MaxDays > 0 {
		cutoff := time.Now().Unix() - int64(cfg.MaxDays*86400)
		res, err := b.msgDB.Exec(`DELETE FROM messages WHERE timestamp < ?`, cutoff)
		if err != nil {
			b.log.Warnf("Prune by age failed: %v", err)
		} else if n, _ := res.RowsAffected(); n > 0 {
			b.log.Infof("Pruned %d messages older than %d days", n, cfg.MaxDays)
		}
		// Clean up reactions for deleted messages
		b.msgDB.Exec(`DELETE FROM reactions WHERE message_id NOT IN (SELECT id FROM messages)`)
	}

	// Prune by count per conversation
	if cfg.MaxMessages > 0 {
		// Collect the conversation JIDs first, then delete — holding a read
		// cursor open while issuing the DELETEs deadlocks any single-writer
		// SQLite (and was unnecessary). NB: the conversations table column is
		// `jid`; the previous query used `conversation_jid` (the messages
		// column), which errored and aborted the whole prune — so neither the
		// per-conversation cap nor the empty-conversation cleanup ever ran.
		var jids []string
		rows, err := b.msgDB.Query(`SELECT DISTINCT conversation_jid FROM messages`)
		if err != nil {
			return
		}
		for rows.Next() {
			var jid string
			if rows.Scan(&jid) == nil {
				jids = append(jids, jid)
			}
		}
		rows.Close()

		for _, jid := range jids {
			res, err := b.msgDB.Exec(`
				DELETE FROM messages WHERE conversation_jid = ? AND id NOT IN (
					SELECT id FROM messages WHERE conversation_jid = ?
					ORDER BY timestamp DESC LIMIT ?
				)
			`, jid, jid, cfg.MaxMessages)
			if err == nil {
				if n, _ := res.RowsAffected(); n > 0 {
					b.log.Debugf("Pruned %d messages from %s (over %d limit)", n, jid, cfg.MaxMessages)
				}
			}
		}
		b.msgDB.Exec(`DELETE FROM reactions WHERE message_id NOT IN (SELECT id FROM messages)`)
	}

	// Remove empty conversations (no messages left), except those holding
	// state that arrives once: a disappearing timer (which Photon's sends
	// must keep carrying), a mute still in force, or an archive.
	b.msgDB.Exec(`DELETE FROM conversations WHERE jid NOT IN (SELECT DISTINCT conversation_jid FROM messages)
		AND disappearing_seconds = 0 AND is_archived = 0 AND muted_until <> -1 AND muted_until <= ?`, time.Now().Unix())
}

func (b *Bridge) pruneMedia() {
	b.mu.RLock()
	ttl := b.retention.MediaTTLMins
	b.mu.RUnlock()

	if ttl <= 0 {
		return
	}

	cutoff := time.Now().Add(-time.Duration(ttl) * time.Minute)
	// Thumbnails (saveThumbnail) follow the same age policy as full media.
	b.pruneMediaDir(filepath.Join(b.dataDir, "media"), cutoff, "media_url")
	b.pruneMediaDir(filepath.Join(b.dataDir, "thumbs"), cutoff, "thumbnail_path")
}

// pruneMediaDir deletes files older than cutoff and NULLs the given messages
// column (files are named <messageID>.<ext>) so the UI stops referencing the
// deleted path and offers re-download where applicable.
func (b *Bridge) pruneMediaDir(dir string, cutoff time.Time, column string) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}

	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		if info.ModTime().Before(cutoff) {
			os.Remove(filepath.Join(dir, entry.Name()))
			msgID := strings.TrimSuffix(entry.Name(), filepath.Ext(entry.Name()))
			b.msgDB.Exec(`UPDATE messages SET `+column+` = NULL WHERE id = ?`, msgID)
		}
	}
}

// BroadcastEvent sends a JSON event to Kotlin.
func (b *Bridge) BroadcastEvent(eventType string, payload interface{}) {
	payloadBytes, err := json.Marshal(payload)
	if err != nil {
		b.log.Errorf("Failed to marshal event payload: %v", err)
		return
	}

	if b.emit != nil {
		b.emit(Envelope{Type: eventType, Payload: payloadBytes})
	}
}

// markMessageFailed flips an outgoing row to "failed" and notifies the UI so
// it can offer retry.
func (b *Bridge) markMessageFailed(jid, id string) {
	b.UpdateMessageStatus(id, "failed")
	b.BroadcastEvent("message_updated", MessageUpdatedEvent{
		ConversationJID: jid, MessageID: id,
	})
}

// markMessageSent records a server-confirmed send. The row adopts the
// server's timestamp (replacing the optimistic local one) so it sorts
// correctly relative to messages that arrived during the send.
func (b *Bridge) markMessageSent(jid, id string, ts int64, timer uint32) {
	b.UpdateMessageStatusAndTimestamp(id, "sent", ts, expiryAfter(ts, timer))
	b.UpsertConversation(jid, rankedName{}, id, ts)
	b.BroadcastEvent("message_updated", MessageUpdatedEvent{
		ConversationJID: jid, MessageID: id,
	})
}

// RequestPairingCode generates an 8-character pairing code for the given phone number.
func (b *Bridge) RequestPairingCode(ctx context.Context, phone string) (string, error) {
	if b.client.Store.ID == nil {
		// Need to connect first before requesting pairing code
		if err := b.client.Connect(); err != nil {
			return "", err
		}
	}
	code, err := b.client.PairPhone(ctx, phone, true, whatsmeow.PairClientChrome, "Chrome (Linux)")
	if err != nil {
		return "", err
	}
	return code, nil
}

// StartQRPairing starts QR code pairing and broadcasts QR codes as events.
func (b *Bridge) StartQRPairing(ctx context.Context) {
	qrChan, _ := b.client.GetQRChannel(ctx)

	if err := b.client.Connect(); err != nil {
		b.BroadcastEvent("pair_error", PairErrorEvent{Error: err.Error()})
		return
	}

	go func() {
		for evt := range qrChan {
			if evt.Event == "code" {
				b.BroadcastEvent("qr_code", QrCodeEvent{Codes: []string{evt.Code}})
			}
		}
	}()
}

// SendTextMessage sends a text message to the given JID.
//
// The message is written to the local DB BEFORE the network send with
// status="sending" using a client-generated stanza ID, so the UI can show
// the outgoing message immediately and reflect the final state once the
// send returns. On failure the status flips to "failed" instead of the row
// being discarded — the UI then offers retry via RetryTextMessage(id),
// which reuses the same stanza ID so WhatsApp's server deduplicates if the
// original happened to reach the server before the error propagated.
func (b *Bridge) SendTextMessage(ctx context.Context, jid string, text string, replyToID string) (string, int64, error) {
	targetJID, err := types.ParseJID(jid)
	if err != nil {
		return "", 0, err
	}
	// Resolve @lid targets so the conversation row keys by phone JID and
	// doesn't duplicate a row handleMessage stored under the resolved JID.
	targetJID = b.resolveLIDJID(targetJID)
	jid = targetJID.String()

	id := b.client.GenerateMessageID()
	ts := time.Now().Unix()

	b.InsertMessage(&MessageRow{
		ID:              id,
		ConversationJID: jid,
		SenderJID:       b.client.Store.ID.String(),
		Timestamp:       ts,
		ContentType:     "text",
		TextBody:        sql.NullString{String: text, Valid: true},
		ReplyToID:       sql.NullString{String: replyToID, Valid: replyToID != ""},
		IsFromMe:        true,
		Status:          "sending",
		ExpiresAt:       expiryAfter(ts, b.sendTimer(jid).seconds),
	})
	b.UpsertConversation(jid, rankedName{}, id, ts)
	b.BroadcastEvent("new_message", b.newMessageEvent(jid, id, b.client.Store.ID.ToNonAD().String(), text, "text", true))

	b.refreshGroupTimer(targetJID)
	ctxInfo, timer := b.outgoingContext(jid, replyToID)
	resp, err := b.sendWithID(ctx, targetJID, buildTextMessage(text, ctxInfo), id)
	if err != nil {
		b.markMessageFailed(jid, id)
		return id, ts, err
	}

	b.markMessageSent(jid, id, resp.Timestamp.Unix(), timer)
	return id, resp.Timestamp.Unix(), nil
}

// RetryTextMessage re-attempts a previously failed outgoing text message,
// reusing the original stanza ID and text. Returns an error if the row
// can't be found, isn't from us, or isn't a text message.
func (b *Bridge) RetryMessage(ctx context.Context, messageID string) error {
	var jid, text, replyToID, contentType, mediaMime, mediaURL string
	err := b.msgDB.QueryRowContext(ctx, `
		SELECT conversation_jid, COALESCE(text_body, ''), COALESCE(reply_to_id, ''),
		       content_type, COALESCE(media_mime, ''), COALESCE(media_url, '')
		  FROM messages WHERE id = ? AND is_from_me = 1
	`, messageID).Scan(&jid, &text, &replyToID, &contentType, &mediaMime, &mediaURL)
	if err != nil {
		return fmt.Errorf("retry: row not found: %w", err)
	}

	targetJID, err := types.ParseJID(jid)
	if err != nil {
		b.markMessageFailed(jid, messageID)
		return err
	}

	b.UpdateMessageStatus(messageID, "sending")
	b.BroadcastEvent("message_updated", MessageUpdatedEvent{
		ConversationJID: jid, MessageID: messageID,
	})

	// Text and media diverge only in how the *.Message is built. Media must
	// re-read the original local source file (media_url for outgoing rows is
	// the file the user picked, not a downloaded copy) and re-upload to the
	// CDN before sending — the original upload's URL/keys aren't persisted.
	// Both reuse the original stanza ID so recipients dedupe a retry that
	// actually went through the first time.
	b.refreshGroupTimer(targetJID)
	ctxInfo, timer := b.outgoingContext(jid, replyToID)
	var msg *waE2E.Message
	if contentType == "text" || contentType == "" {
		if text == "" {
			b.markMessageFailed(jid, messageID)
			return fmt.Errorf("retry: text row has empty body")
		}
		msg = buildTextMessage(text, ctxInfo)
	} else {
		if mediaURL == "" {
			b.markMessageFailed(jid, messageID)
			return fmt.Errorf("retry: media row has no source file path")
		}
		if err := b.sendableFile(mediaURL); err != nil {
			b.markMessageFailed(jid, messageID)
			return fmt.Errorf("retry: %w", err)
		}
		data, err := os.ReadFile(mediaURL)
		if err != nil {
			b.markMessageFailed(jid, messageID)
			return fmt.Errorf("retry: source file gone (%s): %w", mediaURL, err)
		}
		_, mediaType := mediaKind(mediaMime)
		resp, err := b.uploadMedia(ctx, data, mediaType)
		if err != nil {
			b.markMessageFailed(jid, messageID)
			return fmt.Errorf("retry: upload failed: %w", err)
		}
		// text_body holds the caption, or a document's file name.
		msg = buildMediaMessage(resp, mediaMime, mediaTextOf(contentType, text), ctxInfo, data)
	}

	resp, err := b.sendWithID(ctx, targetJID, msg, messageID)
	if err != nil {
		b.markMessageFailed(jid, messageID)
		return err
	}
	b.markMessageSent(jid, messageID, resp.Timestamp.Unix(), timer)
	return nil
}

// DeleteMessage removes a message locally and, when forEveryone is set,
// revokes it on the network first (WhatsApp's "delete for everyone"). The
// revoke reuses the original stanza ID via BuildRevoke. If the revoke send
// fails we leave the local row intact so the user can retry rather than
// being left thinking it was deleted everywhere when it wasn't.
func (b *Bridge) DeleteMessage(ctx context.Context, jid, messageID string, forEveryone bool) error {
	if forEveryone {
		targetJID, err := types.ParseJID(jid)
		if err != nil {
			return err
		}
		targetJID = b.resolveLIDJID(targetJID)
		revoke := b.client.BuildRevoke(targetJID, types.EmptyJID, messageID)
		if _, err := b.client.SendMessage(ctx, targetJID, revoke); err != nil {
			return fmt.Errorf("revoke failed: %w", err)
		}
	}
	b.DeleteMessageRow(messageID)
	b.BroadcastEvent("message_updated", MessageUpdatedEvent{
		ConversationJID: jid, MessageID: messageID,
	})
	return nil
}

// SendTyping sends a typing or paused indicator.
func (b *Bridge) SendTyping(ctx context.Context, jid string, composing bool) error {
	targetJID, err := types.ParseJID(jid)
	if err != nil {
		return err
	}

	state := types.ChatPresenceComposing
	if !composing {
		state = types.ChatPresencePaused
	}
	return b.client.SendChatPresence(ctx, targetJID, state, "")
}

// MarkRead marks messages as read.
//
// For DMs, the sender (participant) is ignored by whatsmeow, so a single call
// covers all message IDs. For group chats the participant MUST be the actual
// user who sent each message, and whatsmeow requires one MarkRead call per
// distinct sender — passing our own JID produced malformed receipts that never
// cleared the unread badge on the primary device. So for groups we look up each
// message's sender and batch the read receipts per sender.
func (b *Bridge) MarkRead(ctx context.Context, jid string, messageIDs []string) error {
	targetJID, err := types.ParseJID(jid)
	if err != nil {
		return err
	}

	isGroup := targetJID.Server == types.GroupServer
	if !isGroup {
		ids := make([]types.MessageID, len(messageIDs))
		for i, id := range messageIDs {
			ids[i] = types.MessageID(id)
		}
		return b.client.MarkRead(ctx, ids, time.Now(), targetJID, types.EmptyJID)
	}

	// Group: bucket message IDs by their sender, then one receipt per sender.
	senders := b.SendersForMessages(messageIDs)
	bySender := make(map[string][]types.MessageID)
	for _, id := range messageIDs {
		sender := senders[id]
		if sender == "" {
			continue
		}
		bySender[sender] = append(bySender[sender], types.MessageID(id))
	}
	var firstErr error
	for sender, ids := range bySender {
		senderJID, perr := types.ParseJID(sender)
		if perr != nil {
			if firstErr == nil {
				firstErr = perr
			}
			continue
		}
		if err := b.client.MarkRead(ctx, ids, time.Now(), targetJID, senderJID); err != nil && firstErr == nil {
			firstErr = err
		}
	}
	return firstErr
}

// Logout disconnects and removes the device from WhatsApp.
func (b *Bridge) Logout() error {
	return b.client.Logout(context.Background())
}

// buildTextMessage is a text message; one with a context (a reply, a
// disappearing timer; see outgoingContext) is an extended text.
func buildTextMessage(text string, ctx *waE2E.ContextInfo) *waE2E.Message {
	if ctx == nil {
		return &waE2E.Message{
			Conversation: proto.String(text),
		}
	}

	return &waE2E.Message{
		ExtendedTextMessage: &waE2E.ExtendedTextMessage{
			Text:        proto.String(text),
			ContextInfo: ctx,
		},
	}
}
