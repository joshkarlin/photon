package main

import (
	"context"
	"database/sql"
	"fmt"
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

// InitMessageDB creates the message database schema if it doesn't exist.
func (b *Bridge) InitMessageDB(ctx context.Context) error {
	statements := []string{
		`CREATE TABLE IF NOT EXISTS conversations (
			jid             TEXT PRIMARY KEY,
			name            TEXT,
			is_group        INTEGER NOT NULL DEFAULT 0,
			last_message_id TEXT,
			last_timestamp  INTEGER NOT NULL DEFAULT 0,
			unread_count    INTEGER NOT NULL DEFAULT 0,
			is_muted        INTEGER NOT NULL DEFAULT 0,
			avatar_url      TEXT,
			updated_at      INTEGER NOT NULL DEFAULT 0
		)`,
		`CREATE TABLE IF NOT EXISTS messages (
			id              TEXT PRIMARY KEY,
			conversation_jid TEXT NOT NULL,
			sender_jid      TEXT NOT NULL,
			timestamp       INTEGER NOT NULL,
			content_type    TEXT NOT NULL,
			text_body       TEXT,
			media_url       TEXT,
			media_mime      TEXT,
			media_size      INTEGER,
			thumbnail_path  TEXT,
			sticker_pack_id TEXT,
			reply_to_id     TEXT,
			edit_version    INTEGER NOT NULL DEFAULT 0,
			is_from_me      INTEGER NOT NULL DEFAULT 0,
			status          TEXT NOT NULL DEFAULT 'sent',
			raw_proto       BLOB,
			FOREIGN KEY (conversation_jid) REFERENCES conversations(jid)
		)`,
		`CREATE INDEX IF NOT EXISTS idx_messages_conv_ts ON messages(conversation_jid, timestamp DESC)`,
		`CREATE TABLE IF NOT EXISTS reactions (
			message_id  TEXT NOT NULL,
			sender_jid  TEXT NOT NULL,
			emoji       TEXT NOT NULL,
			timestamp   INTEGER NOT NULL,
			PRIMARY KEY (message_id, sender_jid)
		)`,
		`CREATE TABLE IF NOT EXISTS participants (
			conversation_jid TEXT NOT NULL,
			jid              TEXT NOT NULL,
			display_name     TEXT,
			role             TEXT DEFAULT 'member',
			PRIMARY KEY (conversation_jid, jid)
		)`,
	}

	for i, stmt := range statements {
		if _, err := b.msgDB.ExecContext(ctx, stmt); err != nil {
			b.log.Errorf("Schema statement %d failed: %v", i, err)
			return fmt.Errorf("schema exec failed: %w", err)
		}
	}
	b.log.Infof("Message DB schema initialized (%d statements)", len(statements))
	return nil
}

// UpsertConversation creates or updates a conversation record.
func (b *Bridge) UpsertConversation(jid, name string, isGroup bool, lastMsgID string, lastTS int64) {
	isGroupInt := 0
	if isGroup {
		isGroupInt = 1
	}

	_, err := b.msgDB.Exec(`
		INSERT INTO conversations (jid, name, is_group, last_message_id, last_timestamp, updated_at)
		VALUES (?, ?, ?, ?, ?, ?)
		ON CONFLICT(jid) DO UPDATE SET
			name = COALESCE(NULLIF(excluded.name, ''), conversations.name),
			is_group = CASE WHEN excluded.is_group = 1 THEN 1 ELSE conversations.is_group END,
			last_message_id = CASE WHEN excluded.last_timestamp > conversations.last_timestamp THEN excluded.last_message_id ELSE conversations.last_message_id END,
			last_timestamp = MAX(excluded.last_timestamp, conversations.last_timestamp),
			updated_at = excluded.updated_at
	`, jid, name, isGroupInt, lastMsgID, lastTS, lastTS)

	if err != nil {
		b.log.Errorf("Failed to upsert conversation %s: %v", jid, err)
	}
}

// IncrementUnread increments the unread count for a conversation.
func (b *Bridge) IncrementUnread(jid string) {
	_, err := b.msgDB.Exec(`UPDATE conversations SET unread_count = unread_count + 1 WHERE jid = ?`, jid)
	if err != nil {
		b.log.Errorf("Failed to increment unread for %s: %v", jid, err)
	}
}

// ResetUnread sets unread count to 0 for a conversation.
func (b *Bridge) ResetUnread(jid string) {
	_, err := b.msgDB.Exec(`UPDATE conversations SET unread_count = 0 WHERE jid = ?`, jid)
	if err != nil {
		b.log.Errorf("Failed to reset unread for %s: %v", jid, err)
	}
}

// UpdateMute sets is_muted for a conversation, inserting the row if it
// doesn't exist yet (mute events can arrive before any message in a chat).
func (b *Bridge) UpdateMute(jid string, muted bool) {
	m := 0
	if muted {
		m = 1
	}
	_, err := b.msgDB.Exec(`
		INSERT INTO conversations (jid, is_muted, updated_at)
		VALUES (?, ?, 0)
		ON CONFLICT(jid) DO UPDATE SET is_muted = excluded.is_muted
	`, jid, m)
	if err != nil {
		b.log.Errorf("Failed to update mute for %s: %v", jid, err)
	}
}

// InsertMessage inserts a message record.
func (b *Bridge) InsertMessage(msg *MessageRow) error {
	isFromMe := 0
	if msg.IsFromMe {
		isFromMe = 1
	}

	_, err := b.msgDB.Exec(`
		INSERT OR IGNORE INTO messages
		(id, conversation_jid, sender_jid, timestamp, content_type, text_body,
		 media_url, media_mime, media_size, thumbnail_path, sticker_pack_id,
		 reply_to_id, edit_version, is_from_me, status, raw_proto)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
	`, msg.ID, msg.ConversationJID, msg.SenderJID, msg.Timestamp,
		msg.ContentType, msg.TextBody, msg.MediaURL, msg.MediaMime,
		msg.MediaSize, msg.ThumbnailPath, msg.StickerPackID,
		msg.ReplyToID, msg.EditVersion, isFromMe, msg.Status, msg.RawProto)

	return err
}

// UpdateMessageStatus updates the delivery status of a message.
func (b *Bridge) UpdateMessageStatus(id, status string) error {
	_, err := b.msgDB.Exec(`UPDATE messages SET status = ? WHERE id = ?`, status, id)
	return err
}

// UpdateMessageEdit updates a message's text after an edit.
func (b *Bridge) UpdateMessageEdit(id, newText string, editVersion int) error {
	_, err := b.msgDB.Exec(`UPDATE messages SET text_body = ?, edit_version = ? WHERE id = ?`, newText, editVersion, id)
	return err
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

// UpsertParticipant inserts or updates a group participant.
func (b *Bridge) UpsertParticipant(convJID, jid, displayName, role string) error {
	_, err := b.msgDB.Exec(`
		INSERT INTO participants (conversation_jid, jid, display_name, role)
		VALUES (?, ?, ?, ?)
		ON CONFLICT(conversation_jid, jid) DO UPDATE SET
			display_name = COALESCE(NULLIF(excluded.display_name, ''), participants.display_name),
			role = excluded.role
	`, convJID, jid, displayName, role)
	return err
}

// UpdateConversationName updates a conversation's display name.
func (b *Bridge) UpdateConversationName(jid, name string) {
	_, err := b.msgDB.Exec(`UPDATE conversations SET name = ? WHERE jid = ?`, name, jid)
	if err != nil {
		b.log.Errorf("Failed to update name for %s: %v", jid, err)
	}
}

// UpdateConversationNameIfEmpty only sets the name if it's currently empty/null.
func (b *Bridge) UpdateConversationNameIfEmpty(jid, name string) {
	_, err := b.msgDB.Exec(`UPDATE conversations SET name = ? WHERE jid = ? AND (name IS NULL OR name = '')`, name, jid)
	if err != nil {
		b.log.Errorf("Failed to update name for %s: %v", jid, err)
	}
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
