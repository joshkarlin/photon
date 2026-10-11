package main

import (
	"database/sql"
	"math"

	"go.mau.fi/whatsmeow/types"
)

// UpsertConversation creates or updates a conversation record. A name only
// fills a missing one (UpdateConversationName overwrites), and the preview
// moves only forward. is_group always follows the JID's server.
func (b *Bridge) UpsertConversation(jid, name, lastMsgID string, lastTS int64) {
	if err := b.upsertConversation(b.msgDB, jid, name, lastMsgID, lastTS); err != nil {
		b.log.Errorf("Failed to upsert conversation %s: %v", jid, err)
	}
}

func (b *Bridge) upsertConversation(ex execer, jid, name, lastMsgID string, lastTS int64) error {
	_, err := ex.Exec(`
		INSERT INTO conversations (jid, name, is_group, last_message_id, last_timestamp, updated_at)
		VALUES (?, ?, ?, ?, ?, ?)
		ON CONFLICT(jid) DO UPDATE SET
			name = COALESCE(NULLIF(excluded.name, ''), conversations.name),
			is_group = excluded.is_group,
			last_message_id = CASE WHEN excluded.last_timestamp > conversations.last_timestamp THEN excluded.last_message_id ELSE conversations.last_message_id END,
			last_timestamp = MAX(excluded.last_timestamp, conversations.last_timestamp),
			updated_at = excluded.updated_at
	`, jid, name, isGroupJID(jid), lastMsgID, lastTS, lastTS)
	return err
}

// isGroupJID reports whether a chat is a group, which its JID's server alone
// decides.
func isGroupJID(jid string) bool {
	parsed, err := types.ParseJID(jid)
	return err == nil && parsed.Server == types.GroupServer
}

// IncrementUnread increments the unread count for a conversation.
func (b *Bridge) IncrementUnread(jid string) {
	_, err := b.msgDB.Exec(`UPDATE conversations SET unread_count = unread_count + 1 WHERE jid = ?`, jid)
	if err != nil {
		b.log.Errorf("Failed to increment unread for %s: %v", jid, err)
	}
}

// MarkChatReadUpTo marks the chat's incoming messages up to ts read; see
// markReadThrough. Returns the remaining unread count.
func (b *Bridge) MarkChatReadUpTo(jid string, ts int64) (remaining int) {
	return b.markReadThrough(jid, ts, math.MaxInt64)
}

// MarkReadThrough marks read the newest stored of the given messages and
// everything before it in its chat. ok is false when none of them is stored.
func (b *Bridge) MarkReadThrough(ids []string) (jid string, remaining int, ok bool) {
	if len(ids) == 0 {
		return "", 0, false
	}
	args := make([]interface{}, len(ids))
	for i, id := range ids {
		args[i] = id
	}
	var ts, rowid int64
	err := b.msgDB.QueryRow(`SELECT conversation_jid, timestamp, _rowid_ FROM messages
		WHERE id IN (`+sqlInList(len(ids))+`) ORDER BY timestamp DESC, _rowid_ DESC LIMIT 1`, args...).Scan(&jid, &ts, &rowid)
	if err != nil {
		return "", 0, false
	}
	return jid, b.markReadThrough(jid, ts, rowid), true
}

// markReadThrough marks the chat's incoming messages read up to a point in
// it — by timestamp, then insertion, the order the chat lists them in — and
// lowers the unread count to the unread messages left after that point. It
// never raises the count, which can include messages pruned or never stored.
// Messages that arrived after the point the user read to stay unread. Returns
// the remaining unread count.
func (b *Bridge) markReadThrough(jid string, ts, rowid int64) (remaining int) {
	err := b.inTx(func(tx *sql.Tx) error {
		if _, err := tx.Exec(`UPDATE messages SET status = 'read'
			WHERE conversation_jid = ? AND is_from_me = 0 AND status NOT IN ('read', 'deleted')
			AND (timestamp < ? OR (timestamp = ? AND _rowid_ <= ?))`, jid, ts, ts, rowid); err != nil {
			return err
		}
		if _, err := tx.Exec(`UPDATE conversations SET unread_count = MIN(unread_count, (
				SELECT COUNT(*) FROM messages
				WHERE conversation_jid = ? AND is_from_me = 0 AND status NOT IN ('read', 'deleted')
			)) WHERE jid = ?`, jid, jid); err != nil {
			return err
		}
		return tx.QueryRow(`SELECT COALESCE((SELECT unread_count FROM conversations WHERE jid = ?), 0)`, jid).Scan(&remaining)
	})
	if err != nil {
		b.log.Errorf("Failed to mark %s read: %v", jid, err)
	}
	return remaining
}

// MarkChatUnread mirrors WhatsApp's "mark as unread": the chat shows as unread
// even though every message in it has been seen.
func (b *Bridge) MarkChatUnread(jid string) {
	if _, err := b.msgDB.Exec(`UPDATE conversations SET unread_count = MAX(unread_count, 1) WHERE jid = ?`, jid); err != nil {
		b.log.Errorf("Failed to mark %s unread: %v", jid, err)
	}
}

// UpdateMute stores a conversation's mute (see migrateMutedUntil for
// muted_until's encoding), inserting the row if it doesn't exist yet (mute
// events can arrive before any message in a chat). is_muted stays as "a mute
// is set"; whether it's still in force depends on the time it's read.
func (b *Bridge) UpdateMute(jid string, mutedUntil int64) {
	if err := updateMute(b.msgDB, jid, mutedUntil); err != nil {
		b.log.Errorf("Failed to update mute for %s: %v", jid, err)
	}
}

func updateMute(ex execer, jid string, mutedUntil int64) error {
	m := 0
	if mutedUntil != 0 {
		m = 1
	}
	_, err := ex.Exec(`
		INSERT INTO conversations (jid, is_group, is_muted, muted_until, updated_at)
		VALUES (?, ?, ?, ?, 0)
		ON CONFLICT(jid) DO UPDATE SET is_muted = excluded.is_muted, muted_until = excluded.muted_until
	`, jid, isGroupJID(jid), m, mutedUntil)
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

// ParticipantName returns a participant's stored display name in a conversation
// (the value that drives the per-message sender label), or "" if absent/blank.
// Reused by mention resolution so a mention reads identically to the label.
func (b *Bridge) ParticipantName(convJID, jid string) string {
	var name string
	b.msgDB.QueryRow(
		`SELECT COALESCE(display_name, '') FROM participants WHERE conversation_jid = ? AND jid = ?`,
		convJID, jid,
	).Scan(&name)
	return name
}

// UpdateConversationName updates a conversation's display name. Returns
// whether the stored name changed.
func (b *Bridge) UpdateConversationName(jid, name string) bool {
	res, err := b.msgDB.Exec(`UPDATE conversations SET name = ? WHERE jid = ? AND name IS NOT ?`, name, jid, name)
	if err != nil {
		b.log.Errorf("Failed to update name for %s: %v", jid, err)
		return false
	}
	n, _ := res.RowsAffected()
	return n > 0
}

// UpdateConversationNameIfEmpty only sets the name if it's currently empty/null.
func (b *Bridge) UpdateConversationNameIfEmpty(jid, name string) {
	_, err := b.msgDB.Exec(`UPDATE conversations SET name = ? WHERE jid = ? AND (name IS NULL OR name = '')`, name, jid)
	if err != nil {
		b.log.Errorf("Failed to update name for %s: %v", jid, err)
	}
}
