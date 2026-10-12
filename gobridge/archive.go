package main

import (
	"context"
	"database/sql"

	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

// Archived chats.
//
// Whether a chat is archived is the primary phone's to say, and Photon
// follows it: conversations.is_archived, with archived_at, the unix second
// the state was set (0 = not known when). Sources:
//
//   - an archive or unarchive on another device (events.Archive), timed;
//   - whatsmeow's own copy of each chat's archive state (ChatSettings),
//     read once when the column is added and after a full app-state sync,
//     untimed;
//   - a history sync's conversations (pairing), untimed;
//   - a new incoming message, which unarchives the chat unless the account
//     keeps chats archived.
//
// A timed state replaces one set no later; an untimed one only fills in a
// chat nothing timed has set. Archived chats still notify unless muted, as
// WhatsApp does: archiving doesn't silence a chat.

// keepArchivedKey is the settings row for WhatsApp's "Keep chats archived".
const keepArchivedKey = "keep_chats_archived"

// setArchived stores a chat's archive state as of at (0 = untimed; see
// above), creating its row if need be. Returns whether is_archived changed.
func setArchived(tx *sql.Tx, jid string, archived bool, at int64) (bool, error) {
	if err := ensureConversation(tx, jid); err != nil {
		return false, err
	}
	var was bool
	var wasAt int64
	if err := tx.QueryRow(`SELECT is_archived, archived_at FROM conversations WHERE jid = ?`, jid).Scan(&was, &wasAt); err != nil {
		return false, err
	}
	if !(at > 0 && at >= wasAt || at == 0 && wasAt == 0) {
		return false, nil
	}
	if _, err := tx.Exec(`UPDATE conversations SET is_archived = ?, archived_at = ? WHERE jid = ?`, archived, max(at, wasAt), jid); err != nil {
		return false, err
	}
	return was != archived, nil
}

// SetArchived is setArchived in its own transaction, telling Kotlin of a
// change.
func (b *Bridge) SetArchived(jid string, archived bool, at int64) {
	var changed bool
	err := b.inTx(func(tx *sql.Tx) error {
		var err error
		changed, err = setArchived(tx, jid, archived, at)
		return err
	})
	if err != nil {
		b.log.Errorf("Failed to store archive state of %s: %v", jid, err)
		return
	}
	if changed {
		b.log.Infof("Chat %s archived=%v", jid, archived)
		b.BroadcastEvent("conversation_updated", ConversationUpdatedEvent{JID: jid})
	}
}

// handleArchive applies an archive or unarchive done on another device.
func (b *Bridge) handleArchive(evt *events.Archive) {
	b.SetArchived(b.resolveLIDJID(evt.JID).String(), evt.Action.GetArchived(), evt.Timestamp.Unix())
}

// handleUnarchiveChatsSetting stores the account's "Keep chats archived"
// setting, changed on another device. WhatsApp syncs its opposite:
// unarchiveChats, whether a new message unarchives a chat.
func (b *Bridge) handleUnarchiveChatsSetting(evt *events.UnarchiveChatsSetting) {
	b.setKeepArchived(!evt.Action.GetUnarchiveChats(), evt.Timestamp.Unix())
}

// setKeepArchived stores "Keep chats archived" as of at; as with a chat's
// archive state, an untimed value (0) never replaces a timed one.
func (b *Bridge) setKeepArchived(keep bool, at int64) {
	value := "0"
	if keep {
		value = "1"
	}
	_, err := b.msgDB.Exec(`INSERT INTO settings (key, value, set_at) VALUES (?, ?, ?)
		ON CONFLICT(key) DO UPDATE SET value = excluded.value, set_at = MAX(excluded.set_at, settings.set_at)
		WHERE (excluded.set_at > 0 AND excluded.set_at >= settings.set_at) OR (excluded.set_at = 0 AND settings.set_at = 0)`,
		keepArchivedKey, value, at)
	if err != nil {
		b.log.Errorf("Failed to store keep-chats-archived: %v", err)
	}
}

// keepChatsArchived is the account's "Keep chats archived" setting. Until
// WhatsApp has said, it's on: WhatsApp's default since 2021.
func (b *Bridge) keepChatsArchived() bool {
	var value string
	if err := b.msgDB.QueryRow(`SELECT value FROM settings WHERE key = ?`, keepArchivedKey).Scan(&value); err != nil {
		return true
	}
	return value != "0"
}

// unarchiveForMessage unarchives a chat for a new incoming message sent at
// ts, unless the account keeps chats archived, or the chat was archived
// after the message was sent.
func (b *Bridge) unarchiveForMessage(jid string, ts int64) {
	if b.keepChatsArchived() {
		return
	}
	res, err := b.msgDB.Exec(`UPDATE conversations SET is_archived = 0, archived_at = ?
		WHERE jid = ? AND is_archived = 1 AND archived_at < ?`, ts, jid, ts)
	if err != nil {
		b.log.Errorf("Failed to unarchive %s: %v", jid, err)
		return
	}
	if n, _ := res.RowsAffected(); n > 0 {
		b.log.Infof("Chat %s unarchived by a new message", jid)
		b.BroadcastEvent("conversation_updated", ConversationUpdatedEvent{JID: jid})
	}
}

// backfillArchived copies each chat's archive state from whatsmeow's chat
// settings into chats nothing timed has set. A DM's settings may sit under
// its phone JID, its LID, or both; it's archived if either says so, as the
// mute backfill takes the longer mute. Without a paired session there's
// nothing to copy.
func (b *Bridge) backfillArchived(ctx context.Context, tx *sql.Tx) (changed []string, err error) {
	if b.client == nil || b.client.Store.ID == nil {
		return nil, nil
	}
	rows, err := tx.QueryContext(ctx, `SELECT jid FROM conversations WHERE archived_at = 0`)
	if err != nil {
		return nil, err
	}
	var jids []string
	for rows.Next() {
		var jid string
		if rows.Scan(&jid) == nil {
			jids = append(jids, jid)
		}
	}
	rows.Close()
	for _, jid := range jids {
		parsed, err := types.ParseJID(jid)
		if err != nil {
			continue
		}
		archived, found := b.storedArchived(ctx, parsed)
		if !found {
			continue
		}
		did, err := setArchived(tx, jid, archived, 0)
		if err != nil {
			return nil, err
		}
		if did {
			changed = append(changed, jid)
		}
	}
	return changed, nil
}

// storedArchived reads whatsmeow's archive state for a chat under each of
// its JID forms.
func (b *Bridge) storedArchived(ctx context.Context, jid types.JID) (archived, found bool) {
	keys := []types.JID{jid}
	if jid.Server == types.DefaultUserServer {
		if lid, err := b.client.Store.LIDs.GetLIDForPN(ctx, jid); err == nil && !lid.IsEmpty() {
			keys = append(keys, lid)
		}
	}
	for _, key := range keys {
		s, err := b.client.Store.ChatSettings.GetChatSettings(ctx, key)
		if err != nil || !s.Found {
			continue
		}
		found = true
		archived = archived || s.Archived
	}
	return archived, found
}

// syncArchivedFromStore runs backfillArchived after a full app-state sync
// (pairing, or recovery), which stores archive states without an event for
// each.
func (b *Bridge) syncArchivedFromStore() {
	var changed []string
	err := b.inTx(func(tx *sql.Tx) error {
		var err error
		changed, err = b.backfillArchived(context.Background(), tx)
		return err
	})
	if err != nil {
		b.log.Errorf("Failed to read archive states: %v", err)
		return
	}
	for _, jid := range changed {
		b.BroadcastEvent("conversation_updated", ConversationUpdatedEvent{JID: jid})
	}
}

// migrateArchived adds the archive state to conversations, and copies it
// from whatsmeow's chat settings, which have kept it all along.
func (b *Bridge) migrateArchived(ctx context.Context, tx *sql.Tx) error {
	for _, column := range []string{"is_archived", "archived_at"} {
		if _, err := addColumnIfMissing(ctx, tx, "conversations", column, "INTEGER NOT NULL DEFAULT 0"); err != nil {
			return err
		}
	}
	changed, err := b.backfillArchived(ctx, tx)
	if err != nil {
		return err
	}
	b.log.Infof("Backfilled archive state for %d chats", len(changed))
	return nil
}
