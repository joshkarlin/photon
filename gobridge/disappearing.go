package main

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"google.golang.org/protobuf/proto"
)

// Disappearing messages.
//
// A chat's timer lives on its conversation row: disappearing_seconds (0 =
// off) and disappearing_set_at, the unix second the setting was made as
// WhatsApp reports it (0 = not known). Photon follows the timer and never
// changes it. Its sources:
//
//   - a setting message (ProtocolMessage EPHEMERAL_SETTING) in a DM;
//   - the ContextInfo every message in a disappearing chat carries
//     (Expiration, EphemeralSettingTimestamp);
//   - a history sync's conversation (EphemeralExpiration,
//     EphemeralSettingTimestamp);
//   - a group's info (a fetch, joining it, or a change notification).
//
// A report with a setting time replaces the stored timer only when it's
// newer, so a late message from before a change never undoes it. One
// without a setting time only fills in a timer nothing timed has set. A
// group's fetched info is the server's current state, so it always wins.
//
// Each message carries its own timer (ContextInfo.Expiration), and WhatsApp
// starts the clock when it's sent: the row's expires_at is its timestamp
// plus that. The prune loop deletes rows past it (expireMessages).

// contentSystem is ContentTypes.SYSTEM: a centred informational line, never
// unread, never notified.
const contentSystem = "system"

// timerSetting is a chat's disappearing-messages timer.
type timerSetting struct {
	seconds uint32
	setAt   int64 // when it was set (unix seconds); 0 = not known
}

// timerReport is one source's word on a chat's timer.
type timerReport struct {
	timerSetting
	// current marks the server's present state (a group's fetched info),
	// which stands whatever the stored setting time.
	current bool
	// at is when the change happened in the chat, where its line goes; 0
	// records the timer without a line.
	at int64
}

// supersedes reports whether r replaces the stored timer cur.
func (r timerReport) supersedes(cur timerSetting) bool {
	switch {
	case r.current:
		return r.seconds != cur.seconds
	case r.setAt > cur.setAt:
		return true
	case r.setAt == 0 && cur.setAt == 0:
		return r.seconds != cur.seconds
	}
	return false
}

// contextTimer is the timer a message's ContextInfo reports; ok is false
// when it says nothing about one (a chat without disappearing messages).
func contextTimer(content *waE2E.Message) (t timerSetting, ok bool) {
	ctx := getContextInfo(content)
	t = timerSetting{seconds: ctx.GetExpiration(), setAt: ctx.GetEphemeralSettingTimestamp()}
	return t, t.seconds != 0 || t.setAt != 0
}

// settingMessageTimer is the timer a setting message (EPHEMERAL_SETTING)
// sets; one without a setting time was set when it was sent.
func settingMessageTimer(pm *waE2E.ProtocolMessage, sentAt int64) timerSetting {
	t := timerSetting{seconds: pm.GetEphemeralExpiration(), setAt: pm.GetEphemeralSettingTimestamp()}
	if t.setAt == 0 {
		t.setAt = sentAt
	}
	return t
}

// messageExpiry is when a message received at ts disappears: its own timer
// from then, or never.
func messageExpiry(content *waE2E.Message, ts int64) int64 {
	if exp := getContextInfo(content).GetExpiration(); exp > 0 {
		return ts + int64(exp)
	}
	return 0
}

// expiryAfter is when a message sent at ts disappears under timer seconds.
func expiryAfter(ts int64, seconds uint32) int64 {
	if seconds == 0 {
		return 0
	}
	return ts + int64(seconds)
}

// timerText is the line a timer change shows in the chat.
func timerText(seconds uint32) string {
	if seconds == 0 {
		return "Disappearing messages off"
	}
	return "Disappearing messages · " + durationText(seconds)
}

func durationText(s uint32) string {
	unit := func(n uint32, name string) string {
		if n == 1 {
			return "1 " + name
		}
		return fmt.Sprintf("%d %ss", n, name)
	}
	switch {
	case s == 86400:
		return "24 hours"
	case s%86400 == 0:
		return unit(s/86400, "day")
	case s%3600 == 0:
		return unit(s/3600, "hour")
	case s%60 == 0:
		return unit(s/60, "minute")
	}
	return unit(s, "second")
}

// timerRow is the SYSTEM line for a timer change at `at`. Its ID is
// Photon's own, so no receipt is ever sent for it (see WithoutCallRows).
func timerRow(chatJID string, seconds uint32, at int64) *MessageRow {
	return &MessageRow{
		ID:              fmt.Sprintf("timer_%d_%d_%s", at, seconds, chatJID),
		ConversationJID: chatJID,
		Timestamp:       at,
		ContentType:     contentSystem,
		TextBody:        sql.NullString{String: timerText(seconds), Valid: true},
		Status:          "read",
	}
}

// chatTimer is a chat's stored timer.
func (b *Bridge) chatTimer(jid string) timerSetting {
	var t timerSetting
	b.msgDB.QueryRow(`SELECT disappearing_seconds, disappearing_set_at FROM conversations WHERE jid = ?`, jid).Scan(&t.seconds, &t.setAt)
	return t
}

// applyTimer stores what a source reports of a chat's timer, if it
// supersedes the stored one, and adds a line to the chat when the timer
// changed (at r.at, unless that's outside the retention window). Kotlin is
// told of any change. Returns whether the timer changed.
func (b *Bridge) applyTimer(chatJID string, r timerReport) bool {
	if !hasTimer(chatJID) {
		return false
	}
	var changed bool
	var line *MessageRow
	err := b.inTx(func(tx *sql.Tx) error {
		var err error
		changed, line, err = b.applyTimerTx(tx, chatJID, r)
		return err
	})
	if err != nil {
		b.log.Errorf("Failed to store the disappearing timer of %s: %v", chatJID, err)
		return false
	}
	if !changed {
		return false
	}
	b.log.Infof("Disappearing timer of %s is now %ds", chatJID, r.seconds)
	if line != nil {
		b.BroadcastEvent("message_updated", MessageUpdatedEvent{ConversationJID: chatJID, MessageID: line.ID})
	}
	b.BroadcastEvent("conversation_updated", ConversationUpdatedEvent{JID: chatJID})
	return true
}

func (b *Bridge) applyTimerTx(tx *sql.Tx, chatJID string, r timerReport) (changed bool, line *MessageRow, err error) {
	if err := ensureConversation(tx, chatJID); err != nil {
		return false, nil, err
	}
	var cur timerSetting
	if err := tx.QueryRow(`SELECT disappearing_seconds, disappearing_set_at FROM conversations WHERE jid = ?`, chatJID).
		Scan(&cur.seconds, &cur.setAt); err != nil {
		return false, nil, err
	}
	if !r.supersedes(cur) {
		return false, nil, nil
	}
	setAt := max(cur.setAt, r.setAt)
	if _, err := tx.Exec(`UPDATE conversations SET disappearing_seconds = ?, disappearing_set_at = ? WHERE jid = ?`,
		r.seconds, setAt, chatJID); err != nil {
		return false, nil, err
	}
	if r.seconds == cur.seconds {
		return false, nil, nil
	}
	if r.at <= 0 || r.at < b.retentionCutoff() {
		return true, nil, nil
	}
	line = timerRow(chatJID, r.seconds, r.at)
	res, err := tx.Exec(insertMessageSQL, line.insertArgs()...)
	if err != nil {
		return false, nil, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return true, nil, nil
	}
	return true, line, b.upsertConversation(tx, chatJID, rankedName{}, line.ID, line.Timestamp)
}

// hasTimer reports whether a chat can have a disappearing timer: a DM or a
// group, not status updates, a broadcast list or a channel.
func hasTimer(chatJID string) bool {
	jid, err := types.ParseJID(chatJID)
	if err != nil {
		return false
	}
	switch jid.Server {
	case types.DefaultUserServer, types.HiddenUserServer, types.GroupServer:
		return true
	}
	return false
}

// retentionCutoff is the oldest timestamp the retention window keeps; 0
// keeps everything.
func (b *Bridge) retentionCutoff() int64 {
	b.mu.RLock()
	days := b.retention.MaxDays
	b.mu.RUnlock()
	if days <= 0 {
		return 0
	}
	return time.Now().Unix() - int64(days*86400)
}

// applyGroupTimer records a group's timer from its info as the server
// holds it now (a fetch, or joining the group).
func (b *Bridge) applyGroupTimer(group types.JID, info *types.GroupInfo) {
	seconds := uint32(0)
	if info.IsEphemeral {
		seconds = info.DisappearingTimer
	}
	b.applyTimer(group.String(), timerReport{timerSetting: timerSetting{seconds: seconds}, current: true, at: time.Now().Unix()})
}

// outgoingContext is the ContextInfo a message Photon sends to jid carries:
// the message it replies to, and the chat's disappearing timer, so it
// disappears for everyone as the chat's other messages do. nil when there's
// neither. Also returns the timer, for the row's expiry.
func (b *Bridge) outgoingContext(jid, replyToID string) (*waE2E.ContextInfo, uint32) {
	t := b.chatTimer(jid)
	var ctx *waE2E.ContextInfo
	if replyToID != "" {
		ctx = &waE2E.ContextInfo{StanzaID: proto.String(replyToID)}
	}
	if t.seconds > 0 {
		if ctx == nil {
			ctx = &waE2E.ContextInfo{}
		}
		ctx.Expiration = proto.Uint32(t.seconds)
		if t.setAt > 0 {
			ctx.EphemeralSettingTimestamp = proto.Int64(t.setAt)
		}
	}
	return ctx, t.seconds
}

// refreshGroupTimer makes sure a group's timer is known before Photon sends
// to it: the group's info is fetched at most hourly (groupEntry), and each
// fetch records the timer.
func (b *Bridge) refreshGroupTimer(jid types.JID) {
	if jid.Server == types.GroupServer {
		b.groupEntry(jid, false)
	}
}

// MessagesRemovedEvent: messages were deleted outright — they disappeared
// (reason "expired"), or were placeholders for something that turned out
// to have nothing to show ("placeholder"). When WasUnread, the chat's
// notification may have shown one: with UnreadCount 0 it should go, and
// NewestUnread, when set, is what it should show instead (the newest
// unread message was among those removed).
type MessagesRemovedEvent struct {
	ConversationJID string           `json:"conversation_jid"`
	MessageIDs      []string         `json:"message_ids"`
	Reason          string           `json:"reason"`
	WasUnread       bool             `json:"was_unread"`
	UnreadCount     int              `json:"unread_count"`
	NewestUnread    *NewMessageEvent `json:"newest_unread,omitempty"`
}

// expireMessages deletes every message whose timer ran out by now.
func (b *Bridge) expireMessages(now int64) {
	rows, err := b.msgDB.Query(`SELECT id FROM messages WHERE expires_at > 0 AND expires_at <= ?`, now)
	if err != nil {
		b.log.Warnf("Expiry query failed: %v", err)
		return
	}
	var ids []string
	for rows.Next() {
		var id string
		if rows.Scan(&id) == nil {
			ids = append(ids, id)
		}
	}
	rows.Close()
	if len(ids) > 0 {
		b.log.Infof("Deleting %d disappeared messages", len(ids))
		b.removeMessages(ids, "expired")
	}
}

// unreadRef is an unread incoming message, as the chat orders it.
type unreadRef struct {
	id, sender, text, contentType string
	ts, rowid                     int64
}

// removal is what removeMessages did to one chat.
type removal struct {
	ids          []string
	unreadGone   int
	newestBefore *unreadRef
}

// removeMessages deletes messages outright, with their reactions and the
// media the bridge downloaded for them, takes the unread ones out of their
// chats' counts, moves each chat's preview back to its newest message left,
// and tells Kotlin per chat (messages_removed).
func (b *Bridge) removeMessages(ids []string, reason string) {
	chats := map[string]*removal{}
	var files []string
	unreadAfter := map[string]int{}
	newestAfter := map[string]*unreadRef{}
	err := b.inTx(func(tx *sql.Tx) error {
		for _, id := range ids {
			var chat, status string
			var fromMe int
			var mediaURL, thumb sql.NullString
			err := tx.QueryRow(`SELECT conversation_jid, is_from_me, status, media_url, thumbnail_path FROM messages WHERE id = ?`, id).
				Scan(&chat, &fromMe, &status, &mediaURL, &thumb)
			if err == sql.ErrNoRows {
				continue
			} else if err != nil {
				return err
			}
			r := chats[chat]
			if r == nil {
				r = &removal{}
				if r.newestBefore, err = newestUnread(tx, chat); err != nil {
					return err
				}
				chats[chat] = r
			}
			r.ids = append(r.ids, id)
			if fromMe == 0 && status != "read" && status != "deleted" {
				r.unreadGone++
			}
			for _, p := range []sql.NullString{mediaURL, thumb} {
				if p.Valid && p.String != "" {
					files = append(files, p.String)
				}
			}
			for _, q := range []string{`DELETE FROM messages WHERE id = ?`, `DELETE FROM reactions WHERE message_id = ?`} {
				if _, err := tx.Exec(q, id); err != nil {
					return err
				}
			}
		}
		for chat, r := range chats {
			if _, err := tx.Exec(`UPDATE conversations SET unread_count = MAX(unread_count - ?, 0) WHERE jid = ?`, r.unreadGone, chat); err != nil {
				return err
			}
			if err := movePreviewBack(tx, chat); err != nil {
				return err
			}
			var unread int
			if err := tx.QueryRow(`SELECT COALESCE((SELECT unread_count FROM conversations WHERE jid = ?), 0)`, chat).Scan(&unread); err != nil {
				return err
			}
			unreadAfter[chat] = unread
			newest, err := newestUnread(tx, chat)
			if err != nil {
				return err
			}
			newestAfter[chat] = newest
		}
		return nil
	})
	if err != nil {
		b.log.Errorf("Failed to delete %d messages (%s): %v", len(ids), reason, err)
		return
	}
	for _, f := range files {
		if b.isBridgeMediaPath(f) || b.isStagedPath(f) {
			os.Remove(f)
		}
	}
	for chat, r := range chats {
		evt := MessagesRemovedEvent{
			ConversationJID: chat,
			MessageIDs:      r.ids,
			Reason:          reason,
			WasUnread:       r.unreadGone > 0,
			UnreadCount:     unreadAfter[chat],
		}
		// The notification shows the newest unread message; if that went
		// and others are left, it should show the newest of those.
		if evt.WasUnread && evt.UnreadCount > 0 && r.newestBefore != nil && containsID(r.ids, r.newestBefore.id) {
			if n := newestAfter[chat]; n != nil {
				ne := b.newMessageEvent(chat, n.id, n.sender, n.text, n.contentType, false)
				evt.NewestUnread = &ne
			}
		}
		b.BroadcastEvent("messages_removed", evt)
	}
}

// newestUnread is the chat's newest unread incoming message, if any.
func newestUnread(tx *sql.Tx, chat string) (*unreadRef, error) {
	var u unreadRef
	err := tx.QueryRow(`SELECT id, sender_jid, COALESCE(text_body, ''), content_type, timestamp, _rowid_ FROM messages
		WHERE conversation_jid = ? AND is_from_me = 0 AND status NOT IN ('read', 'deleted') AND content_type <> ?
		ORDER BY timestamp DESC, _rowid_ DESC LIMIT 1`, chat, contentSystem).
		Scan(&u.id, &u.sender, &u.text, &u.contentType, &u.ts, &u.rowid)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &u, nil
}

// movePreviewBack points a chat's preview at its newest message left, if
// any is.
func movePreviewBack(ex execer, jid string) error {
	_, err := ex.Exec(`UPDATE conversations SET
			last_timestamp  = (SELECT MAX(timestamp) FROM messages WHERE conversation_jid = conversations.jid),
			last_message_id = (SELECT id FROM messages WHERE conversation_jid = conversations.jid ORDER BY timestamp DESC, _rowid_ DESC LIMIT 1)
		WHERE jid = ? AND EXISTS (SELECT 1 FROM messages WHERE conversation_jid = conversations.jid)`, jid)
	return err
}

func containsID(ids []string, id string) bool {
	for _, x := range ids {
		if x == id {
			return true
		}
	}
	return false
}

// isStagedPath reports whether path is a file Kotlin staged for a send
// (see sendableFile), which only the sent row refers to.
func (b *Bridge) isStagedPath(path string) bool {
	clean := filepath.Clean(path)
	name := filepath.Base(clean)
	return filepath.Dir(clean) == filepath.Clean(b.dataDir) &&
		(strings.HasPrefix(name, "send_media_") || strings.HasPrefix(name, "voice_"))
}

// migrateDisappearing adds the disappearing timer to conversations and an
// expiry to messages. Builds before it ignored timers, but every message
// stored with its proto still carries its own (ContextInfo.Expiration):
// those rows get their expiry, and each chat takes the timer its newest
// such message reports. Rows already past their expiry go at the next
// expiry run.
func (b *Bridge) migrateDisappearing(ctx context.Context, tx *sql.Tx) error {
	for _, c := range []struct{ table, column string }{
		{"conversations", "disappearing_seconds"},
		{"conversations", "disappearing_set_at"},
		{"messages", "expires_at"},
	} {
		if _, err := addColumnIfMissing(ctx, tx, c.table, c.column, "INTEGER NOT NULL DEFAULT 0"); err != nil {
			return err
		}
	}
	if _, err := tx.ExecContext(ctx, `CREATE INDEX IF NOT EXISTS idx_messages_expires ON messages(expires_at) WHERE expires_at > 0`); err != nil {
		return err
	}

	type evidence struct {
		id     string
		expiry int64
		timer  timerSetting
		ok     bool
	}
	rows, err := tx.QueryContext(ctx, `SELECT id, conversation_jid, timestamp, raw_proto FROM messages
		WHERE raw_proto IS NOT NULL AND content_type NOT IN ('call', ?) ORDER BY timestamp, _rowid_`, contentSystem)
	if err != nil {
		return err
	}
	var found []evidence
	newest := map[string]evidence{}
	for rows.Next() {
		var id, chat string
		var ts int64
		var raw []byte
		if rows.Scan(&id, &chat, &ts, &raw) != nil {
			continue
		}
		var msg waE2E.Message
		if proto.Unmarshal(raw, &msg) != nil {
			continue
		}
		e := evidence{id: id, expiry: messageExpiry(&msg, ts)}
		e.timer, e.ok = contextTimer(&msg)
		found = append(found, e)
		newest[chat] = e
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, e := range found {
		if e.expiry == 0 {
			continue
		}
		if _, err := tx.ExecContext(ctx, `UPDATE messages SET expires_at = ? WHERE id = ?`, e.expiry, e.id); err != nil {
			return err
		}
	}
	timed := 0
	for chat, e := range newest {
		if !e.ok || e.timer.seconds == 0 {
			continue
		}
		if _, _, err := b.applyTimerTx(tx, chat, timerReport{timerSetting: e.timer}); err != nil {
			return err
		}
		timed++
	}
	b.log.Infof("Backfilled disappearing timers for %d chats", timed)
	return nil
}
