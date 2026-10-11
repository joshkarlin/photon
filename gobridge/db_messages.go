package main

import (
	"database/sql"
	"fmt"
	"strings"
)

// MessageRow represents a row in the messages table.
type MessageRow struct {
	ID              string
	ConversationJID string
	SenderJID       string
	Timestamp       int64
	ContentType     string
	TextBody        sql.NullString
	MediaURL        sql.NullString
	MediaMime       sql.NullString
	MediaSize       sql.NullInt64
	ThumbnailPath   sql.NullString
	StickerPackID   sql.NullString
	ReplyToID       sql.NullString
	EditVersion     int
	IsFromMe        bool
	Status          string
	RawProto        []byte
}

// SendersForMessages returns the sender_jid for each of the given message IDs,
// keyed by message ID. Used by MarkRead to attribute group read receipts to the
// participant who actually sent each message (whatsmeow requires the correct
// participant, and one MarkRead call per distinct sender).
func (b *Bridge) SendersForMessages(ids []string) map[string]string {
	out := make(map[string]string, len(ids))
	if len(ids) == 0 {
		return out
	}
	placeholders := make([]string, len(ids))
	args := make([]interface{}, len(ids))
	for i, id := range ids {
		placeholders[i] = "?"
		args[i] = id
	}
	query := `SELECT id, sender_jid FROM messages WHERE id IN (` + strings.Join(placeholders, ",") + `)`
	rows, err := b.msgDB.Query(query, args...)
	if err != nil {
		b.log.Errorf("SendersForMessages query failed: %v", err)
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var id, sender string
		if err := rows.Scan(&id, &sender); err != nil {
			continue
		}
		out[id] = sender
	}
	return out
}

// WithoutCallRows drops call rows from a list of message IDs. Their IDs are
// Photon's own ("call_<id>"), not message IDs WhatsApp could take a read
// receipt for.
func (b *Bridge) WithoutCallRows(ids []string) []string {
	if len(ids) == 0 {
		return ids
	}
	args := make([]interface{}, len(ids))
	for i, id := range ids {
		args[i] = id
	}
	calls := map[string]bool{}
	rows, err := b.msgDB.Query(`SELECT id FROM messages WHERE content_type = 'call' AND id IN (`+sqlInList(len(ids))+`)`, args...)
	if err == nil {
		for rows.Next() {
			var id string
			if rows.Scan(&id) == nil {
				calls[id] = true
			}
		}
		rows.Close()
	}
	out := make([]string, 0, len(ids))
	for _, id := range ids {
		if !calls[id] && !strings.HasPrefix(id, "call_") {
			out = append(out, id)
		}
	}
	return out
}

// RevokeResult describes a message blanked by a "delete for everyone".
type RevokeResult struct {
	ConversationJID string
	WasUnread       bool     // it was an incoming message the user hadn't read
	Unread          int      // the conversation's unread count afterwards
	Files           []string // downloaded media/thumbnail paths now orphaned
}

// RevokeMessage blanks a message deleted for everyone so it renders as
// "[ deleted ]" (text type, no body, status "deleted"), drops its reactions,
// and takes it out of the unread count if it was still unread. Only a message
// in chatJID sent by `by` matches (nil: any sender). found is false when no
// such message is stored (pruned, outside the history window, or not theirs);
// err is a database failure, after which nothing has changed.
func (b *Bridge) RevokeMessage(id, chatJID string, by *author) (res RevokeResult, found bool, err error) {
	err = b.inTx(func(tx *sql.Tx) error {
		query := `SELECT conversation_jid, is_from_me, status, media_url, thumbnail_path FROM messages WHERE id = ? AND conversation_jid = ?`
		args := []interface{}{id, chatJID}
		if by != nil {
			clause, extra := by.sqlClause()
			query += " AND " + clause
			args = append(args, extra...)
		}
		var isFromMe int
		var status string
		var mediaURL, thumbPath sql.NullString
		err := tx.QueryRow(query, args...).Scan(&res.ConversationJID, &isFromMe, &status, &mediaURL, &thumbPath)
		if err == sql.ErrNoRows {
			return nil
		}
		if err != nil {
			return err
		}
		found = true
		res.WasUnread = isFromMe == 0 && status != "read" && status != "deleted"
		// Only bridge-owned downloads are removed — an outgoing row's media_url is
		// the user's own staged file.
		if isFromMe == 0 {
			for _, p := range []sql.NullString{mediaURL, thumbPath} {
				if p.Valid && p.String != "" {
					res.Files = append(res.Files, p.String)
				}
			}
		}

		if _, err := tx.Exec(`UPDATE messages SET content_type = 'text', text_body = NULL, media_url = NULL,
			media_mime = NULL, media_size = NULL, thumbnail_path = NULL, raw_proto = NULL, status = 'deleted'
			WHERE id = ?`, id); err != nil {
			return err
		}
		if _, err := tx.Exec(`DELETE FROM reactions WHERE message_id = ?`, id); err != nil {
			return err
		}
		if res.WasUnread {
			if _, err := tx.Exec(`UPDATE conversations SET unread_count = MAX(unread_count - 1, 0) WHERE jid = ?`, res.ConversationJID); err != nil {
				return err
			}
		}
		return tx.QueryRow(`SELECT COALESCE((SELECT unread_count FROM conversations WHERE jid = ?), 0)`, res.ConversationJID).Scan(&res.Unread)
	})
	if err != nil {
		return RevokeResult{}, false, err
	}
	return res, found, nil
}

const insertMessageSQL = `
	INSERT OR IGNORE INTO messages
	(id, conversation_jid, sender_jid, timestamp, content_type, text_body,
	 media_url, media_mime, media_size, thumbnail_path, sticker_pack_id,
	 reply_to_id, edit_version, is_from_me, status, raw_proto)
	VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`

func (m *MessageRow) insertArgs() []interface{} {
	isFromMe := 0
	if m.IsFromMe {
		isFromMe = 1
	}
	return []interface{}{
		m.ID, m.ConversationJID, m.SenderJID, m.Timestamp,
		m.ContentType, m.TextBody, m.MediaURL, m.MediaMime,
		m.MediaSize, m.ThumbnailPath, m.StickerPackID,
		m.ReplyToID, m.EditVersion, isFromMe, m.Status, m.RawProto,
	}
}

// ensureConversation creates an empty conversation row for a message about
// to be stored. A message row needs its conversation (foreign key), and the
// first message to or from someone new, a call from a new number, or history
// for a chat not yet seen live comes before any. Callers upsert the
// conversation's name and last message after the insert, as before; a row
// left empty (last_timestamp 0) isn't listed.
func ensureConversation(ex execer, jid string) error {
	_, err := ex.Exec(`INSERT INTO conversations (jid, is_group) VALUES (?, ?) ON CONFLICT(jid) DO NOTHING`,
		jid, isGroupJID(jid))
	return err
}

// InsertMessage inserts a message record, creating its conversation if it
// has none. inserted is false when a row with the same ID already exists —
// a redelivery of something already stored.
func (b *Bridge) InsertMessage(msg *MessageRow) (inserted bool, err error) {
	if err := ensureConversation(b.msgDB, msg.ConversationJID); err != nil {
		return false, err
	}
	res, err := b.msgDB.Exec(insertMessageSQL, msg.insertArgs()...)
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	return n > 0, err
}

// ReplaceMessageContent overwrites a stored message's content with a fuller
// copy of it (the phone's resend of a placeholder). Returns false when no
// row matched.
func (b *Bridge) ReplaceMessageContent(m *MessageRow) bool {
	res, err := b.msgDB.Exec(`UPDATE messages SET content_type = ?, text_body = ?, media_mime = ?,
		reply_to_id = ?, raw_proto = ? WHERE id = ? AND status <> 'deleted'`,
		m.ContentType, m.TextBody, m.MediaMime, m.ReplyToID, m.RawProto, m.ID)
	if err != nil {
		b.log.Errorf("Failed to replace content of %s: %v", m.ID, err)
		return false
	}
	n, _ := res.RowsAffected()
	return n > 0
}

// InsertMessages inserts a batch of messages in a single transaction. Used by
// history sync, where one implicit transaction per row would mean thousands
// of WAL commits during initial pairing. The live path uses InsertMessage.
func (b *Bridge) InsertMessages(msgs []*MessageRow) error {
	if len(msgs) == 0 {
		return nil
	}
	tx, err := b.msgDB.Begin()
	if err != nil {
		// Fall back to per-row inserts rather than dropping history.
		for _, m := range msgs {
			b.InsertMessage(m)
		}
		return err
	}
	ensured := map[string]bool{}
	for _, m := range msgs {
		if ensured[m.ConversationJID] {
			continue
		}
		if err := ensureConversation(tx, m.ConversationJID); err != nil {
			tx.Rollback()
			return err
		}
		ensured[m.ConversationJID] = true
	}
	stmt, err := tx.Prepare(insertMessageSQL)
	if err != nil {
		tx.Rollback()
		return err
	}
	for _, m := range msgs {
		stmt.Exec(m.insertArgs()...)
	}
	stmt.Close()
	return tx.Commit()
}

// UpdateMessageStatus updates the delivery status of a message.
func (b *Bridge) UpdateMessageStatus(id, status string) error {
	_, err := b.msgDB.Exec(`UPDATE messages SET status = ? WHERE id = ?`, status, id)
	return err
}

// outgoingStatusesBelow lists the statuses a receipt may advance from. Status
// only moves forward (a late "delivered" never downgrades "read"), a receipt
// proves a "failed" send actually went through, and "deleted" is final.
var outgoingStatusesBelow = map[string][]string{
	"delivered": {"sending", "sent", "failed"},
	"read":      {"sending", "sent", "failed", "delivered"},
}

// AdvanceOutgoingStatus applies a contact's delivered/read receipt to our own
// outgoing rows (receipts often cover many IDs at once). Incoming rows with a
// matching ID are never touched.
func (b *Bridge) AdvanceOutgoingStatus(ids []string, status string) error {
	from := outgoingStatusesBelow[status]
	if len(ids) == 0 || len(from) == 0 {
		return nil
	}
	args := make([]interface{}, 0, 1+len(ids)+len(from))
	args = append(args, status)
	for _, id := range ids {
		args = append(args, id)
	}
	for _, s := range from {
		args = append(args, s)
	}
	_, err := b.msgDB.Exec(`UPDATE messages SET status = ? WHERE id IN (`+sqlInList(len(ids))+
		`) AND is_from_me = 1 AND status IN (`+sqlInList(len(from))+`)`, args...)
	return err
}

// UpdateMessageStatusAndTimestamp updates both fields together. Used when a
// "sending" row needs to flip to "sent" and adopt the server-confirmed
// timestamp on success.
func (b *Bridge) UpdateMessageStatusAndTimestamp(id, status string, ts int64) error {
	_, err := b.msgDB.Exec(`UPDATE messages SET status = ?, timestamp = ? WHERE id = ?`, status, ts, id)
	return err
}

// UpdateMessageEdit replaces a message's text (the caption, for media) after
// an edit and bumps its edit version. Only a message in chatJID sent by `by`
// matches; deleted messages stay deleted. Returns whether one was edited.
func (b *Bridge) UpdateMessageEdit(id, chatJID, newText string, by *author) (bool, error) {
	clause, extra := by.sqlClause()
	args := append([]interface{}{newText, id, chatJID}, extra...)
	res, err := b.msgDB.Exec(`UPDATE messages SET text_body = ?, edit_version = edit_version + 1
		WHERE id = ? AND conversation_jid = ? AND status <> 'deleted' AND `+clause, args...)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

// UpsertReaction inserts or updates a reaction. Empty emoji removes the reaction.
func (b *Bridge) UpsertReaction(msgID, senderJID, emoji string, ts int64) error {
	if emoji == "" {
		_, err := b.msgDB.Exec(`DELETE FROM reactions WHERE message_id = ? AND sender_jid = ?`, msgID, senderJID)
		return err
	}
	_, err := b.msgDB.Exec(`
		INSERT INTO reactions (message_id, sender_jid, emoji, timestamp)
		VALUES (?, ?, ?, ?)
		ON CONFLICT(message_id, sender_jid) DO UPDATE SET emoji = excluded.emoji, timestamp = excluded.timestamp
	`, msgID, senderJID, emoji, ts)
	return err
}

// GetMessageRawProto retrieves the raw protobuf bytes for a message.
func (b *Bridge) GetMessageRawProto(msgID string) ([]byte, error) {
	var raw []byte
	err := b.msgDB.QueryRow(`SELECT raw_proto FROM messages WHERE id = ?`, msgID).Scan(&raw)
	if err != nil {
		return nil, fmt.Errorf("message %s not found: %w", msgID, err)
	}
	return raw, nil
}

// DeleteMessageRow removes a message and its reactions from the local DB, and
// moves its chat's preview back to the newest message left.
func (b *Bridge) DeleteMessageRow(msgID string) {
	var jid string
	b.msgDB.QueryRow(`SELECT conversation_jid FROM messages WHERE id = ?`, msgID).Scan(&jid)
	if _, err := b.msgDB.Exec(`DELETE FROM messages WHERE id = ?`, msgID); err != nil {
		b.log.Warnf("Failed to delete message %s: %v", msgID, err)
	}
	b.msgDB.Exec(`DELETE FROM reactions WHERE message_id = ?`, msgID)
	b.msgDB.Exec(`UPDATE conversations SET
			last_timestamp  = (SELECT MAX(timestamp) FROM messages WHERE conversation_jid = conversations.jid),
			last_message_id = (SELECT id FROM messages WHERE conversation_jid = conversations.jid ORDER BY timestamp DESC, _rowid_ DESC LIMIT 1)
		WHERE jid = ? AND EXISTS (SELECT 1 FROM messages WHERE conversation_jid = conversations.jid)`, jid)
}

// UpdateMediaURL sets the local media path for a downloaded message.
func (b *Bridge) UpdateMediaURL(msgID, path string) error {
	_, err := b.msgDB.Exec(`UPDATE messages SET media_url = ? WHERE id = ?`, path, msgID)
	return err
}

// UpdateThumbnailPath sets the thumbnail path for a message.
func (b *Bridge) UpdateThumbnailPath(msgID, path string) error {
	_, err := b.msgDB.Exec(`UPDATE messages SET thumbnail_path = ? WHERE id = ?`, path, msgID)
	return err
}
