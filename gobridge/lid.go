package main

import (
	"context"
	"database/sql"

	"go.mau.fi/whatsmeow/types"
)

// resolveLIDJID resolves an @lid JID to its phone-based JID when the mapping
// is known. Returns the input unchanged for non-LID JIDs or unknown mappings.
// All conversation keys must go through this so LID and phone events land in
// the same row.
func (b *Bridge) resolveLIDJID(jid types.JID) types.JID {
	if jid.Server != types.HiddenUserServer {
		return jid
	}
	if pn, err := b.client.Store.LIDs.GetPNForLID(context.Background(), jid); err == nil && !pn.IsEmpty() {
		return pn
	}
	return jid
}

// resolveLIDString is resolveLIDJID for raw JID strings (request params,
// history sync payloads).
func (b *Bridge) resolveLIDString(jidStr string) string {
	parsed, err := types.ParseJID(jidStr)
	if err != nil {
		return jidStr
	}
	return b.resolveLIDJID(parsed).String()
}

// canonicalSenderJID returns the stable key for a message sender: LID resolved
// to the phone JID and the device/agent suffix (":N") stripped. The same person
// must key identically across history sync and live messages, and the contact
// store is keyed without the device suffix — without ToNonAD a live sender like
// "447...:4@s.whatsapp.net" never matches its contact.
func (b *Bridge) canonicalSenderJID(jid types.JID) string {
	return b.resolveLIDJID(jid).ToNonAD().String()
}

// canonicalSenderString is canonicalSenderJID for a raw JID string.
func (b *Bridge) canonicalSenderString(jidStr string) string {
	parsed, err := types.ParseJID(jidStr)
	if err != nil {
		return jidStr
	}
	return b.canonicalSenderJID(parsed)
}

// MergeLIDConversations folds conversation rows keyed by @lid JIDs into
// their phone-based rows. Duplicates appeared when an event path stored a
// LID key (history sync, the old push-name handler) while live messages
// used the resolved phone JID. Runs when app state sync lands the mappings
// (plus a post-connect fallback); unresolvable LIDs are left untouched and
// retried on the next run.
func (b *Bridge) MergeLIDConversations() {
	rows, err := b.msgDB.Query(`SELECT jid FROM conversations WHERE jid LIKE '%@lid'`)
	if err != nil {
		return
	}
	var lids []string
	for rows.Next() {
		var jid string
		if rows.Scan(&jid) == nil {
			lids = append(lids, jid)
		}
	}
	rows.Close()

	merged := 0
	for _, lid := range lids {
		pn := b.resolveLIDString(lid)
		if pn == lid {
			continue // mapping not known yet
		}
		ok, err := b.mergeConversation(lid, pn)
		if err != nil {
			b.log.Errorf("Failed to merge LID conversation %s into %s: %v", lid, pn, err)
			continue
		}
		if ok {
			b.log.Infof("Merged LID conversation %s into %s", lid, pn)
			merged++
		}
	}
	if merged > 0 {
		b.log.Infof("Merged %d LID conversations into phone-based rows", merged)
		b.BroadcastEvent("conversation_updated", struct{}{})
	}
}

// mergeConversation moves one conversation's messages, participants, unread
// count and mute onto another, atomically. The source row is re-read inside
// the transaction, so a merge that already happened is a no-op (false)
// rather than adding the unread count again.
func (b *Bridge) mergeConversation(from, to string) (bool, error) {
	tx, err := b.msgDB.Begin()
	if err != nil {
		return false, err
	}
	defer tx.Rollback()

	var lastMsgID string
	var name rankedName
	var unread int
	var lastTS, mutedUntil int64
	var timer timerSetting
	err = tx.QueryRow(`SELECT COALESCE(name, ''), name_rank, COALESCE(last_message_id, ''), last_timestamp, unread_count, muted_until,
		disappearing_seconds, disappearing_set_at
		FROM conversations WHERE jid = ?`, from).Scan(&name.name, &name.rank, &lastMsgID, &lastTS, &unread, &mutedUntil,
		&timer.seconds, &timer.setAt)
	if err == sql.ErrNoRows {
		return false, nil
	}
	if err != nil {
		return false, err
	}

	// Re-key messages and participants, then merge the conversation row.
	// upsertConversation moves the target's last message only forward, and
	// the source's name, as it may be the older of the two, only fills a
	// missing or weaker one — exactly the merge we want.
	for _, q := range []string{
		`UPDATE messages SET conversation_jid = ? WHERE conversation_jid = ?`,
		`UPDATE OR IGNORE participants SET conversation_jid = ? WHERE conversation_jid = ?`,
	} {
		if _, err := tx.Exec(q, to, from); err != nil {
			return false, err
		}
	}
	if _, err := tx.Exec(`DELETE FROM participants WHERE conversation_jid = ?`, from); err != nil {
		return false, err
	}
	name.stale = true
	if err := b.upsertConversation(tx, to, name, lastMsgID, lastTS); err != nil {
		return false, err
	}
	if _, err := tx.Exec(`UPDATE conversations SET unread_count = unread_count + ?,
		muted_until = CASE WHEN ? = -1 OR muted_until = -1 THEN -1 ELSE MAX(muted_until, ?) END,
		is_muted = CASE WHEN ? <> 0 OR muted_until <> 0 THEN 1 ELSE 0 END
		WHERE jid = ?`, unread, mutedUntil, mutedUntil, mutedUntil, to); err != nil {
		return false, err
	}
	// The disappearing timer set more recently under either JID stands.
	if timer != (timerSetting{}) {
		if _, _, err := b.applyTimerTx(tx, to, timerReport{timerSetting: timer}); err != nil {
			return false, err
		}
	}
	if _, err := tx.Exec(`DELETE FROM conversations WHERE jid = ?`, from); err != nil {
		return false, err
	}
	return true, tx.Commit()
}

// CanonicalizeSenderJIDs re-keys message, reaction, and participant sender JIDs
// from @lid to the resolved phone JID once the LID→PN mapping is known. This is
// the sender-side counterpart to MergeLIDConversations, which only re-keys
// conversation_jid — group chats live under @g.us, so a member stored under an
// @lid sender (history sync, older builds) was never reconciled and split from
// their phone-keyed live messages. Idempotent; runs in the same backfill cycle.
func (b *Bridge) CanonicalizeSenderJIDs() {
	rows, err := b.msgDB.Query(`SELECT DISTINCT sender_jid FROM messages WHERE sender_jid LIKE '%@lid'`)
	if err != nil {
		return
	}
	var lids []string
	for rows.Next() {
		var s string
		if rows.Scan(&s) == nil && s != "" {
			lids = append(lids, s)
		}
	}
	rows.Close()

	changed := 0
	for _, lid := range lids {
		pn := b.resolveLIDString(lid)
		if pn == lid {
			continue // mapping not known yet — retry on the next sync patch
		}
		// messages.sender_jid isn't unique, so a plain update is safe; the
		// PK-bearing tables use UPDATE OR IGNORE + delete to fold duplicates,
		// a member's LID row first lending the phone row its name if that's
		// missing or weaker.
		if err := b.inTx(func(tx *sql.Tx) error {
			for _, q := range []string{
				`UPDATE participants SET display_name = l.display_name, name_rank = l.name_rank
					FROM participants AS l
					WHERE participants.jid = ? AND l.jid = ? AND l.conversation_jid = participants.conversation_jid
					AND COALESCE(l.display_name, '') <> ''
					AND (COALESCE(participants.display_name, '') = '' OR l.name_rank > participants.name_rank)`,
				`UPDATE messages SET sender_jid = ? WHERE sender_jid = ?`,
				`UPDATE OR IGNORE reactions SET sender_jid = ? WHERE sender_jid = ?`,
				`UPDATE OR IGNORE participants SET jid = ? WHERE jid = ?`,
			} {
				if _, err := tx.Exec(q, pn, lid); err != nil {
					return err
				}
			}
			for _, q := range []string{
				`DELETE FROM reactions WHERE sender_jid = ?`,
				`DELETE FROM participants WHERE jid = ?`,
			} {
				if _, err := tx.Exec(q, lid); err != nil {
					return err
				}
			}
			return nil
		}); err != nil {
			b.log.Errorf("Failed to canonicalize sender %s: %v", lid, err)
			continue
		}
		changed++
	}
	if changed > 0 {
		b.log.Infof("Canonicalized %d LID sender(s) to phone JIDs", changed)
		b.BroadcastEvent("conversation_updated", struct{}{})
	}
}
