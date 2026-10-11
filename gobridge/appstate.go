package main

import (
	"context"
	"database/sql"
	"time"

	"go.mau.fi/whatsmeow/proto/waSyncAction"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

// handleMarkChatAsRead applies "mark as read/unread" done on another device.
// The action carries the newest message the user had seen, so messages that
// arrived after it stay unread.
func (b *Bridge) handleMarkChatAsRead(evt *events.MarkChatAsRead) {
	jid := b.resolveLIDJID(evt.JID).String()
	readUpTo := evt.Action.GetMessageRange().GetLastMessageTimestamp()
	if readUpTo == 0 && !evt.Timestamp.IsZero() {
		readUpTo = evt.Timestamp.Unix()
	}
	if readUpTo == 0 {
		readUpTo = time.Now().Unix()
	}
	b.applyChatReadState(jid, evt.Action.GetRead(), readUpTo)
}

func (b *Bridge) applyChatReadState(jid string, read bool, readUpTo int64) {
	if !read {
		b.MarkChatUnread(jid)
		b.BroadcastEvent("conversation_updated", ConversationUpdatedEvent{JID: jid})
		return
	}
	if b.MarkChatReadUpTo(jid, readUpTo) == 0 {
		b.BroadcastEvent("read_self", ReadSelfEvent{ConversationJID: jid})
	} else {
		b.BroadcastEvent("conversation_updated", ConversationUpdatedEvent{JID: jid})
	}
}

// handleMute syncs WhatsApp mute state from the primary device. The end time
// is stored rather than a flag, so a timed mute lapses on its own.
func (b *Bridge) handleMute(evt *events.Mute) {
	// Resolve LID to phone JID so keys match our conversations table.
	jid := b.resolveLIDJID(evt.JID).String()
	until := mutedUntil(evt.Action)
	b.UpdateMute(jid, until)
	b.BroadcastEvent("conversation_updated", ConversationUpdatedEvent{JID: jid})
	b.log.Infof("Mute update: %s muted_until=%d", jid, until)
}

// mutedUntil converts a mute action to muted_until (0 = not muted, -1 =
// forever, else the unix second it ends). The action's end time is in
// milliseconds, and WhatsApp marks "always" with -1 (see appstate.BuildMute);
// a mute with no end at all is treated as "always" too.
func mutedUntil(act *waSyncAction.MuteAction) int64 {
	if !act.GetMuted() {
		return 0
	}
	endMs := act.GetMuteEndTimestamp()
	if endMs <= 0 {
		return -1
	}
	return (endMs + 999) / 1000
}

// backfillMutedUntil copies each chat's mute end time from whatsmeow's chat
// settings, for migrateMutedUntil. Without a paired session there are none
// to copy.
func (b *Bridge) backfillMutedUntil(ctx context.Context, tx *sql.Tx) error {
	if b.client == nil || b.client.Store.ID == nil {
		return nil
	}
	rows, err := tx.QueryContext(ctx, `SELECT jid, muted_until FROM conversations`)
	if err != nil {
		return err
	}
	stored := map[string]int64{}
	for rows.Next() {
		var jid string
		var until int64
		if rows.Scan(&jid, &until) == nil {
			stored[jid] = until
		}
	}
	rows.Close()

	fixed := 0
	for jid, until := range stored {
		parsed, err := types.ParseJID(jid)
		if err != nil {
			continue
		}
		want, ok := b.storedMutedUntil(ctx, parsed)
		if !ok || want == until {
			continue
		}
		if err := updateMute(tx, jid, want); err != nil {
			return err
		}
		fixed++
	}
	b.log.Infof("Backfilled mute end time for %d chats", fixed)
	return nil
}

// storedMutedUntil reads whatsmeow's mute for a chat. A DM's settings may sit
// under its phone JID, its LID, or both (say a pin under one and the mute
// under the other), so the longer mute of the two wins.
func (b *Bridge) storedMutedUntil(ctx context.Context, jid types.JID) (until int64, found bool) {
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
		until = longerMute(until, mutedUntilFromSettings(s.MutedUntil))
	}
	return until, found
}

// longerMute picks the mute that lasts longer (-1 = forever, 0 = none).
func longerMute(x, y int64) int64 {
	if x == -1 || y == -1 {
		return -1
	}
	if y > x {
		return y
	}
	return x
}

// mutedUntilFromSettings converts whatsmeow's stored mute end to muted_until.
func mutedUntilFromSettings(t time.Time) int64 {
	switch {
	case t.IsZero():
		return 0
	case !t.Before(store.MutedForever):
		return -1
	}
	return t.Unix()
}
