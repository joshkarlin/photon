package main

import (
	"context"
	"database/sql"
	"os"
	"time"

	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

// Messages Photon can't show, shown as what they are rather than dropped.
const (
	// contentViewOnce is ContentTypes.VIEW_ONCE: view-once media, never
	// downloaded, previewed or saved. text_body: "photo", "video", "voice",
	// or null when WhatsApp didn't say which.
	contentViewOnce = "view_once"
	// contentUnavailable is ContentTypes.UNAVAILABLE: a message this linked
	// device couldn't decrypt (yet). text_body: null. Replaced in place when
	// it's resent.
	contentUnavailable = "unavailable"
)

// placeholderCondition matches the rows a message resent under the same ID
// replaces: one that couldn't be decrypted, and a view-once notice stored
// without its message.
const placeholderCondition = `(content_type = '` + contentUnavailable + `' OR (content_type = '` + contentViewOnce + `' AND raw_proto IS NULL))`

// handleUndecryptable stores a message that couldn't be decrypted as a
// placeholder under its ID, counted and announced like any message: either
// view-once media (WhatsApp never gives that to linked devices) or an
// "unavailable" message. whatsmeow asks the sender (and the phone) to
// resend it; the copy replaces the placeholder in place (handleMessage).
// Messages the sender marked to hide on failure (reactions, edits and such)
// aren't shown, nor are status updates and other non-chats.
func (b *Bridge) handleUndecryptable(evt *events.UndecryptableMessage) {
	info := evt.Info
	if evt.DecryptFailMode == events.DecryptFailHide || !hasTimer(info.Chat.String()) {
		return
	}
	chatJID := b.resolveLIDJID(info.Chat).String()
	senderJID := b.canonicalSenderJID(info.Sender)
	ts := info.Timestamp.Unix()
	row := &MessageRow{
		ID:              info.ID,
		ConversationJID: chatJID,
		SenderJID:       senderJID,
		Timestamp:       ts,
		ContentType:     contentUnavailable,
		IsFromMe:        info.IsFromMe,
		Status:          "sent",
		ExpiresAt:       expiryAfter(ts, b.chatTimer(chatJID).seconds),
	}
	if evt.UnavailableType == events.UnavailableTypeViewOnce {
		row.ContentType = contentViewOnce
		kind := viewOnceMediaKind(info.MediaType)
		row.TextBody = sql.NullString{String: kind, Valid: kind != ""}
	}
	if row.ExpiresAt > 0 && row.ExpiresAt <= time.Now().Unix() {
		return
	}
	b.log.Infof("Message %s in %s couldn't be decrypted (unavailable=%v, type %q); stored as %s",
		info.ID, chatJID, evt.IsUnavailable, evt.UnavailableType, row.ContentType)
	inserted, err := b.InsertMessage(row)
	if err != nil {
		b.log.Errorf("Failed to store placeholder %s in %s: %v", info.ID, chatJID, err)
		return
	}
	if !inserted {
		// Repeated (a resend that failed too), or the message itself is
		// already stored.
		return
	}
	b.upsertMessageConversation(info, chatJID, senderJID, info.ID, ts)
	b.announceNewMessage(chatJID, info.ID, senderJID, row.TextBody.String, row.ContentType, info.IsFromMe, ts)
}

// viewOnceMediaKind names the media a message's stanza says it carries.
func viewOnceMediaKind(mediaType string) string {
	switch mediaType {
	case "image":
		return "photo"
	case "video", "gif", "ptv":
		return "video"
	case "ptt", "audio":
		return "voice"
	}
	return ""
}

// viewOnceKind reports whether content is view-once media, and which:
// "photo", "video" or "voice". wrapped is whether it came in a view-once
// wrapper (events.Message.IsViewOnce); the media also flags itself.
func viewOnceKind(wrapped bool, content *waE2E.Message) (string, bool) {
	img, vid, aud := content.GetImageMessage(), videoOf(content), content.GetAudioMessage()
	if !wrapped && !img.GetViewOnce() && !vid.GetViewOnce() && !aud.GetViewOnce() {
		return "", false
	}
	switch {
	case img != nil:
		return "photo", true
	case vid != nil:
		return "video", true
	case aud != nil:
		return "voice", true
	}
	return "", false
}

// applyViewOnce makes a row of view-once media a VIEW_ONCE notice: its kind,
// and nothing that could fetch or show the media (the proto holds its keys,
// and its preview). Returns whether it was view-once.
func applyViewOnce(row *MessageRow, wrapped bool, content *waE2E.Message) bool {
	kind, ok := viewOnceKind(wrapped, content)
	if !ok {
		return false
	}
	row.ContentType = contentViewOnce
	row.TextBody = sql.NullString{String: kind, Valid: true}
	row.MediaMime = sql.NullString{}
	row.RawProto = nil
	return true
}

// replacePlaceholder turns a stored placeholder into the message it stood
// for, keeping its place, unread state and expiry. Only its sender's
// message in the same chat replaces it (message IDs are visible to every
// group member), and, unless it's the phone's own resend (fromPhone, which
// may upgrade any row it was asked for), only a placeholder. Returns
// whether a row changed.
func (b *Bridge) replacePlaceholder(m *MessageRow, info types.MessageInfo, fromPhone bool) bool {
	cond := placeholderCondition
	if fromPhone {
		cond = "1"
	}
	// A copy identical to what's stored (a redelivery) changes nothing.
	query := `UPDATE messages SET content_type = ?, text_body = ?, media_url = ?, media_mime = ?, reply_to_id = ?, raw_proto = ?,
		expires_at = CASE WHEN ? > 0 THEN ? ELSE expires_at END
		WHERE id = ? AND conversation_jid = ? AND status <> 'deleted' AND ` + cond + `
		AND (content_type IS NOT ? OR text_body IS NOT ? OR raw_proto IS NOT ?)`
	args := []interface{}{
		m.ContentType, m.TextBody, m.MediaURL, m.MediaMime, m.ReplyToID, m.RawProto, m.ExpiresAt, m.ExpiresAt,
		m.ID, m.ConversationJID,
		m.ContentType, m.TextBody, m.RawProto,
	}
	if info.Chat.Server == types.GroupServer {
		clause, extra := b.authorOf(info).sqlClause()
		query += " AND " + clause
		args = append(args, extra...)
	}
	res, err := b.msgDB.Exec(query, args...)
	if err != nil {
		b.log.Errorf("Failed to replace placeholder %s: %v", m.ID, err)
		return false
	}
	n, _ := res.RowsAffected()
	return n > 0
}

// dropPlaceholder deletes the placeholder stored under a message's ID when
// the message, resent, turns out to have nothing to show (a reaction, a
// setting, a message already past its timer…).
func (b *Bridge) dropPlaceholder(chatJID string, info types.MessageInfo) {
	query := `SELECT id FROM messages WHERE id = ? AND conversation_jid = ? AND ` + placeholderCondition
	args := []interface{}{info.ID, chatJID}
	if info.Chat.Server == types.GroupServer {
		clause, extra := b.authorOf(info).sqlClause()
		query += " AND " + clause
		args = append(args, extra...)
	}
	var id string
	if b.msgDB.QueryRow(query, args...).Scan(&id) != nil {
		return
	}
	b.removeMessages([]string{id}, "placeholder")
}

// MessageReplacedEvent: a placeholder became the message it stood for (see
// replacePlaceholder), with new_message's fields. NewestUnread is true when
// it's the newest unread message in its chat, which a notification for the
// placeholder showed: it should now show this, without alerting again.
type MessageReplacedEvent struct {
	NewMessageEvent
	NewestUnread bool `json:"newest_unread"`
}

func (b *Bridge) messageReplacedEvent(m *MessageRow) MessageReplacedEvent {
	var id string
	err := b.msgDB.QueryRow(`SELECT id FROM messages WHERE conversation_jid = ? AND is_from_me = 0
		AND status NOT IN ('read', 'deleted') AND content_type <> ? ORDER BY timestamp DESC, _rowid_ DESC LIMIT 1`,
		m.ConversationJID, contentSystem).Scan(&id)
	return MessageReplacedEvent{
		NewMessageEvent: b.newMessageEvent(m.ConversationJID, m.ID, m.SenderJID, m.TextBody.String, m.ContentType, m.IsFromMe),
		NewestUnread:    err == nil && id == m.ID,
	}
}

// migrateViewOnce turns view-once media earlier builds stored as ordinary
// photos, videos and voice notes into VIEW_ONCE notices, and deletes the
// media and previews they downloaded.
func (b *Bridge) migrateViewOnce(ctx context.Context, tx *sql.Tx) error {
	rows, err := tx.QueryContext(ctx, `SELECT id, raw_proto, COALESCE(media_url, ''), COALESCE(thumbnail_path, '') FROM messages
		WHERE content_type IN ('image', 'video', 'audio') AND raw_proto IS NOT NULL AND is_from_me = 0`)
	if err != nil {
		return err
	}
	type found struct {
		id, kind string
		files    []string
	}
	var views []found
	for rows.Next() {
		var f found
		var raw []byte
		var media, thumb string
		if rows.Scan(&f.id, &raw, &media, &thumb) != nil {
			continue
		}
		var msg waE2E.Message
		if proto.Unmarshal(raw, &msg) != nil {
			continue
		}
		kind, ok := viewOnceKind(false, &msg)
		if !ok {
			continue
		}
		f.kind, f.files = kind, []string{media, thumb}
		views = append(views, f)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, f := range views {
		if _, err := tx.ExecContext(ctx, `UPDATE messages SET content_type = ?, text_body = ?, media_url = NULL, media_mime = NULL,
			thumbnail_path = NULL, raw_proto = NULL WHERE id = ?`, contentViewOnce, f.kind, f.id); err != nil {
			return err
		}
		for _, p := range f.files {
			if p != "" && b.isBridgeMediaPath(p) {
				os.Remove(p)
			}
		}
	}
	b.log.Infof("Marked %d stored messages view-once", len(views))
	return nil
}
