package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"sync"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/types"
	waLog "go.mau.fi/whatsmeow/util/log"
	"nhooyr.io/websocket"
)

// RetentionConfig controls how long messages and media are kept.
type RetentionConfig struct {
	MaxMessages  int   // Max messages per conversation (0 = unlimited)
	MaxDays      int   // Max age in days (0 = unlimited)
	MediaTTLMins int   // Minutes before temp media files are deleted
}

// Bridge holds the whatsmeow client, message DB, and WebSocket connections.
type Bridge struct {
	client  *whatsmeow.Client
	msgDB   *sql.DB
	dataDir string
	log     waLog.Logger

	mu        sync.RWMutex
	conns     []*websocket.Conn
	retention RetentionConfig
}

// NewBridge creates a new Bridge instance.
func NewBridge(client *whatsmeow.Client, msgDB *sql.DB, dataDir string, log waLog.Logger) *Bridge {
	return &Bridge{
		client:  client,
		msgDB:   msgDB,
		dataDir: dataDir,
		log:     log,
		retention: RetentionConfig{
			MaxMessages:  50,
			MaxDays:      7,
			MediaTTLMins: 5,
		},
	}
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

// StartPruning runs periodic cleanup of old messages and temp media.
func (b *Bridge) StartPruning(ctx context.Context) {
	go func() {
		ticker := time.NewTicker(5 * time.Minute)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				b.pruneMessages()
				b.pruneMedia()
			}
		}
	}()
}

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
		rows, err := b.msgDB.Query(`SELECT DISTINCT conversation_jid FROM conversations`)
		if err != nil {
			return
		}
		defer rows.Close()

		for rows.Next() {
			var jid string
			rows.Scan(&jid)
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

	// Remove empty conversations (no messages left)
	b.msgDB.Exec(`DELETE FROM conversations WHERE jid NOT IN (SELECT DISTINCT conversation_jid FROM messages)`)
}

func (b *Bridge) pruneMedia() {
	b.mu.RLock()
	ttl := b.retention.MediaTTLMins
	b.mu.RUnlock()

	if ttl <= 0 {
		return
	}

	cutoff := time.Now().Add(-time.Duration(ttl) * time.Minute)
	mediaDir := b.dataDir + "/media"

	entries, err := os.ReadDir(mediaDir)
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
			path := mediaDir + "/" + entry.Name()
			os.Remove(path)
			// Clear media_url in DB so the message shows as downloadable again
			msgID := entry.Name()
			// Strip extension to get message ID
			for i := len(msgID) - 1; i >= 0; i-- {
				if msgID[i] == '.' {
					msgID = msgID[:i]
					break
				}
			}
			b.msgDB.Exec(`UPDATE messages SET media_url = NULL WHERE id = ?`, msgID)
		}
	}
}

// AddConn registers a WebSocket connection for event broadcasting.
func (b *Bridge) AddConn(conn *websocket.Conn) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.conns = append(b.conns, conn)
}

// RemoveConn unregisters a WebSocket connection.
func (b *Bridge) RemoveConn(conn *websocket.Conn) {
	b.mu.Lock()
	defer b.mu.Unlock()
	for i, c := range b.conns {
		if c == conn {
			b.conns = append(b.conns[:i], b.conns[i+1:]...)
			return
		}
	}
}

// BroadcastEvent sends a JSON event to all connected WebSocket clients.
func (b *Bridge) BroadcastEvent(eventType string, payload interface{}) {
	payloadBytes, err := json.Marshal(payload)
	if err != nil {
		b.log.Errorf("Failed to marshal event payload: %v", err)
		return
	}

	msg := WsMessage{
		Type:    eventType,
		Payload: payloadBytes,
	}
	msgBytes, err := json.Marshal(msg)
	if err != nil {
		b.log.Errorf("Failed to marshal event: %v", err)
		return
	}

	b.mu.RLock()
	defer b.mu.RUnlock()
	for _, conn := range b.conns {
		conn.Write(context.Background(), websocket.MessageText, msgBytes)
	}
}

// Connect connects to WhatsApp. Call after pairing or on startup with existing session.
func (b *Bridge) Connect(ctx context.Context) error {
	b.BroadcastEvent("connection_state", ConnectionStateEvent{State: "connecting"})
	err := b.client.Connect()
	if err != nil {
		b.BroadcastEvent("connection_state", ConnectionStateEvent{State: "disconnected"})
		return err
	}
	return nil
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
	})
	b.UpsertConversation(jid, "", false, id, ts)
	b.BroadcastEvent("new_message", NewMessageEvent{
		ConversationJID: jid, MessageID: id, TextBody: text,
		ContentType: "text", IsFromMe: true,
	})

	msg := b.buildTextMessage(text, replyToID)
	resp, err := b.client.SendMessage(ctx, targetJID, msg, whatsmeow.SendRequestExtra{ID: id})
	if err != nil {
		b.UpdateMessageStatus(id, "failed")
		b.BroadcastEvent("message_updated", MessageUpdatedEvent{
			ConversationJID: jid, MessageID: id,
		})
		return id, ts, err
	}

	// Replace the placeholder timestamp with the server-confirmed one so the
	// row sorts correctly relative to messages that arrived during the send.
	b.UpdateMessageStatusAndTimestamp(id, "sent", resp.Timestamp.Unix())
	b.UpsertConversation(jid, "", false, id, resp.Timestamp.Unix())
	b.BroadcastEvent("message_updated", MessageUpdatedEvent{
		ConversationJID: jid, MessageID: id,
	})

	return id, resp.Timestamp.Unix(), nil
}

// RetryTextMessage re-attempts a previously failed outgoing text message,
// reusing the original stanza ID and text. Returns an error if the row
// can't be found, isn't from us, or isn't a text message.
func (b *Bridge) RetryTextMessage(ctx context.Context, messageID string) error {
	var jid, text, replyToID string
	var status string
	err := b.msgDB.QueryRowContext(ctx, `
		SELECT conversation_jid, COALESCE(text_body, ''), COALESCE(reply_to_id, ''), status
		  FROM messages WHERE id = ? AND is_from_me = 1
	`, messageID).Scan(&jid, &text, &replyToID, &status)
	if err != nil {
		return fmt.Errorf("retry: row not found: %w", err)
	}
	if text == "" {
		return fmt.Errorf("retry: only text messages supported")
	}

	b.UpdateMessageStatus(messageID, "sending")
	b.BroadcastEvent("message_updated", MessageUpdatedEvent{
		ConversationJID: jid, MessageID: messageID,
	})

	targetJID, err := types.ParseJID(jid)
	if err != nil {
		b.UpdateMessageStatus(messageID, "failed")
		b.BroadcastEvent("message_updated", MessageUpdatedEvent{
			ConversationJID: jid, MessageID: messageID,
		})
		return err
	}

	msg := b.buildTextMessage(text, replyToID)
	resp, err := b.client.SendMessage(ctx, targetJID, msg, whatsmeow.SendRequestExtra{ID: messageID})
	if err != nil {
		b.UpdateMessageStatus(messageID, "failed")
		b.BroadcastEvent("message_updated", MessageUpdatedEvent{
			ConversationJID: jid, MessageID: messageID,
		})
		return err
	}
	b.UpdateMessageStatusAndTimestamp(messageID, "sent", resp.Timestamp.Unix())
	b.UpsertConversation(jid, "", false, messageID, resp.Timestamp.Unix())
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
func (b *Bridge) MarkRead(ctx context.Context, jid string, messageIDs []string) error {
	targetJID, err := types.ParseJID(jid)
	if err != nil {
		return err
	}

	ids := make([]types.MessageID, len(messageIDs))
	for i, id := range messageIDs {
		ids[i] = types.MessageID(id)
	}

	return b.client.MarkRead(ctx, ids, time.Now(), targetJID, b.client.Store.ID.ToNonAD())
}

// Logout disconnects and removes the device from WhatsApp.
func (b *Bridge) Logout() error {
	return b.client.Logout(context.Background())
}
