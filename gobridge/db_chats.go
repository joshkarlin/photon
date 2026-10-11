package main

import (
	"database/sql"
	"math"

	"go.mau.fi/whatsmeow/types"
)

// UpsertConversation creates or updates a conversation record. The name is
// written as setConversationName writes it, and the preview moves only
// forward. is_group always follows the JID's server.
func (b *Bridge) UpsertConversation(jid string, name rankedName, lastMsgID string, lastTS int64) {
	if err := b.upsertConversation(b.msgDB, jid, name, lastMsgID, lastTS); err != nil {
		b.log.Errorf("Failed to upsert conversation %s: %v", jid, err)
	}
}

func (b *Bridge) upsertConversation(ex execer, jid string, name rankedName, lastMsgID string, lastTS int64) error {
	_, err := ex.Exec(`
		INSERT INTO conversations (jid, name, is_group, last_message_id, last_timestamp, updated_at)
		VALUES (?, '', ?, ?, ?, ?)
		ON CONFLICT(jid) DO UPDATE SET
			is_group = excluded.is_group,
			last_message_id = CASE WHEN excluded.last_timestamp > conversations.last_timestamp THEN excluded.last_message_id ELSE conversations.last_message_id END,
			last_timestamp = MAX(excluded.last_timestamp, conversations.last_timestamp),
			updated_at = excluded.updated_at
	`, jid, isGroupJID(jid), lastMsgID, lastTS, lastTS)
	if err != nil {
		return err
	}
	_, err = setConversationName(ex, jid, name)
	return err
}

// nameWriteAllowed is the condition under which a name write (args from
// rankedName.writeArgs) replaces the name a row holds in col: something
// would change, and the row's name is missing, ranks lower, or ranks the
// same and the write isn't stale.
func nameWriteAllowed(col string) string {
	return `(` + col + ` IS NOT ? OR name_rank <> ?) AND (COALESCE(` + col + `, '') = '' OR name_rank < ? OR (name_rank = ? AND ?))`
}

func (n rankedName) writeArgs() []interface{} {
	return []interface{}{n.name, n.rank, n.rank, n.rank, !n.stale}
}

// setConversationName stores a conversation's name if it beats the stored
// one (see nameWriteAllowed). An empty name never clears one. Returns whether
// the row changed.
func setConversationName(ex execer, jid string, n rankedName) (bool, error) {
	if n.name == "" {
		return false, nil
	}
	args := append([]interface{}{n.name, n.rank, jid}, n.writeArgs()...)
	res, err := ex.Exec(`UPDATE conversations SET name = ?, name_rank = ? WHERE jid = ? AND `+nameWriteAllowed("name"), args...)
	if err != nil {
		return false, err
	}
	changed, _ := res.RowsAffected()
	return changed > 0, nil
}

// SetConversationName is setConversationName on the message DB.
func (b *Bridge) SetConversationName(jid string, n rankedName) bool {
	changed, err := setConversationName(b.msgDB, jid, n)
	if err != nil {
		b.log.Errorf("Failed to update name for %s: %v", jid, err)
	}
	return changed
}

// storedConversationName is a conversation's stored name and its rank.
func (b *Bridge) storedConversationName(jid string) rankedName {
	var n rankedName
	b.msgDB.QueryRow(`SELECT COALESCE(name, ''), name_rank FROM conversations WHERE jid = ?`, jid).Scan(&n.name, &n.rank)
	return n
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

// UpsertParticipant names a group member, adding their row if it's new. The
// name replaces a stored one as in setConversationName; an empty name adds
// nothing. Returns whether anything changed.
func (b *Bridge) UpsertParticipant(convJID, jid string, n rankedName) bool {
	changed, err := upsertParticipant(b.msgDB, convJID, jid, n)
	if err != nil {
		b.log.Errorf("Failed to name participant %s in %s: %v", jid, convJID, err)
	}
	return changed
}

func upsertParticipant(ex execer, convJID, jid string, n rankedName) (bool, error) {
	if n.name == "" {
		return false, nil
	}
	res, err := ex.Exec(`INSERT OR IGNORE INTO participants (conversation_jid, jid, display_name, name_rank)
		VALUES (?, ?, ?, ?)`, convJID, jid, n.name, n.rank)
	if err != nil {
		return false, err
	}
	if inserted, _ := res.RowsAffected(); inserted > 0 {
		return true, nil
	}
	args := append([]interface{}{n.name, n.rank, convJID, jid}, n.writeArgs()...)
	res, err = ex.Exec(`UPDATE participants SET display_name = ?, name_rank = ?
		WHERE conversation_jid = ? AND jid = ? AND `+nameWriteAllowed("display_name"), args...)
	if err != nil {
		return false, err
	}
	changed, _ := res.RowsAffected()
	return changed > 0, nil
}

// ParticipantName returns a participant's stored display name in a conversation
// (the value that drives the per-message sender label), or "" if absent/blank.
// Reused by mention resolution so a mention reads identically to the label.
func (b *Bridge) ParticipantName(convJID, jid string) string {
	return b.storedParticipantName(convJID, jid).name
}

// storedParticipantName is a participant's stored name and its rank.
func (b *Bridge) storedParticipantName(convJID, jid string) rankedName {
	var n rankedName
	b.msgDB.QueryRow(
		`SELECT COALESCE(display_name, ''), name_rank FROM participants WHERE conversation_jid = ? AND jid = ?`,
		convJID, jid,
	).Scan(&n.name, &n.rank)
	return n
}
