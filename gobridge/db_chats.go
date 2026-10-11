package main

// UpsertConversation creates or updates a conversation record.
func (b *Bridge) UpsertConversation(jid, name string, isGroup bool, lastMsgID string, lastTS int64) {
	if err := b.upsertConversation(b.msgDB, jid, name, isGroup, lastMsgID, lastTS); err != nil {
		b.log.Errorf("Failed to upsert conversation %s: %v", jid, err)
	}
}

func (b *Bridge) upsertConversation(ex execer, jid, name string, isGroup bool, lastMsgID string, lastTS int64) error {
	isGroupInt := 0
	if isGroup {
		isGroupInt = 1
	}

	_, err := ex.Exec(`
		INSERT INTO conversations (jid, name, is_group, last_message_id, last_timestamp, updated_at)
		VALUES (?, ?, ?, ?, ?, ?)
		ON CONFLICT(jid) DO UPDATE SET
			name = COALESCE(NULLIF(excluded.name, ''), conversations.name),
			-- Only a row carrying a name is an authoritative classification (the
			-- incoming/history/merge paths). A nameless upsert is just a preview
			-- bump from the send/confirm path, which must never reclassify — it
			-- once flipped a group to is_group=0 and switched the chat to DM view.
			-- RepairConversationMetadata fixes any stale classification by JID.
			is_group = CASE WHEN NULLIF(excluded.name, '') IS NULL THEN conversations.is_group ELSE excluded.is_group END,
			last_message_id = CASE WHEN excluded.last_timestamp > conversations.last_timestamp THEN excluded.last_message_id ELSE conversations.last_message_id END,
			last_timestamp = MAX(excluded.last_timestamp, conversations.last_timestamp),
			updated_at = excluded.updated_at
	`, jid, name, isGroupInt, lastMsgID, lastTS, lastTS)
	return err
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

// MarkChatReadUpTo marks the chat's incoming messages up to ts read and
// lowers the unread count to the messages that arrived after ts (never raising
// it — the count can include messages pruned or never stored). Returns the
// remaining unread count.
func (b *Bridge) MarkChatReadUpTo(jid string, ts int64) (remaining int) {
	if _, err := b.msgDB.Exec(`UPDATE messages SET status = 'read'
		WHERE conversation_jid = ? AND is_from_me = 0 AND status NOT IN ('read', 'deleted') AND timestamp <= ?`, jid, ts); err != nil {
		b.log.Errorf("Failed to mark %s read: %v", jid, err)
	}
	if _, err := b.msgDB.Exec(`UPDATE conversations SET unread_count = MIN(unread_count, (
			SELECT COUNT(*) FROM messages
			WHERE conversation_jid = ? AND is_from_me = 0 AND status NOT IN ('read', 'deleted') AND timestamp > ?
		)) WHERE jid = ?`, jid, ts, jid); err != nil {
		b.log.Errorf("Failed to recount unread for %s: %v", jid, err)
	}
	b.msgDB.QueryRow(`SELECT unread_count FROM conversations WHERE jid = ?`, jid).Scan(&remaining)
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
		INSERT INTO conversations (jid, is_muted, muted_until, updated_at)
		VALUES (?, ?, ?, 0)
		ON CONFLICT(jid) DO UPDATE SET is_muted = excluded.is_muted, muted_until = excluded.muted_until
	`, jid, m, mutedUntil)
	return err
}

// MarkIncomingRead records that the user read a conversation on another
// device: the listed incoming rows become "read" and the unread badge clears.
func (b *Bridge) MarkIncomingRead(jid string, ids []string) {
	if len(ids) > 0 {
		args := make([]interface{}, 0, len(ids)+1)
		args = append(args, jid)
		for _, id := range ids {
			args = append(args, id)
		}
		if _, err := b.msgDB.Exec(`UPDATE messages SET status = 'read' WHERE conversation_jid = ? AND is_from_me = 0 AND status <> 'deleted' AND id IN (`+sqlInList(len(ids))+`)`, args...); err != nil {
			b.log.Errorf("Failed to mark messages read in %s: %v", jid, err)
		}
	}
	b.ResetUnread(jid)
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

// purgeNumberPlaceholders removes participant rows whose display_name is a bare
// "+<digits>" phone number — placeholders an earlier build wrote when no real
// name was known, which then shadowed real push/contact names. The GLOB matches
// a leading "+" followed by a digit and excludes anything containing a letter,
// so real names like "+1 Dad" are preserved. Returns the number of rows removed.
func (b *Bridge) purgeNumberPlaceholders(groupJID string) int64 {
	res, err := b.msgDB.Exec(
		`DELETE FROM participants WHERE conversation_jid = ? `+
			`AND display_name GLOB '+[0-9]*' AND display_name NOT GLOB '*[A-Za-z]*'`,
		groupJID,
	)
	if err != nil {
		b.log.Errorf("purgeNumberPlaceholders failed: %v", err)
		return 0
	}
	n, _ := res.RowsAffected()
	return n
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
