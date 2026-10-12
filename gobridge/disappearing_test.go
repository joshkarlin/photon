package main

import (
	"context"
	"database/sql"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
	"google.golang.org/protobuf/proto"
)

const (
	day  = 86400
	week = 7 * day
)

func storedTimer(t *testing.T, b *Bridge, jid string) timerSetting {
	t.Helper()
	var ts timerSetting
	if err := b.msgDB.QueryRow(`SELECT disappearing_seconds, disappearing_set_at FROM conversations WHERE jid = ?`, jid).
		Scan(&ts.seconds, &ts.setAt); err != nil {
		t.Fatalf("timer of %s: %v", jid, err)
	}
	return ts
}

// systemLines is a chat's SYSTEM rows' text, oldest first.
func systemLines(t *testing.T, b *Bridge, jid string) []string {
	t.Helper()
	rows, err := b.msgDB.Query(`SELECT text_body FROM messages WHERE conversation_jid = ? AND content_type = 'system'
		ORDER BY timestamp, _rowid_`, jid)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var s string
		rows.Scan(&s)
		out = append(out, s)
	}
	return out
}

func expiresAt(t *testing.T, b *Bridge, id string) int64 {
	t.Helper()
	var at int64
	if err := b.msgDB.QueryRow(`SELECT expires_at FROM messages WHERE id = ?`, id).Scan(&at); err != nil {
		t.Fatalf("expiry of %s: %v", id, err)
	}
	return at
}

// settingEvent is a DM's EPHEMERAL_SETTING message.
func settingEvent(chat, sender types.JID, id string, ts int64, seconds uint32, setAt int64) *events.Message {
	return messageEvent(chat, sender, id, ts, &waE2E.Message{ProtocolMessage: &waE2E.ProtocolMessage{
		Type:                      waE2E.ProtocolMessage_EPHEMERAL_SETTING.Enum(),
		EphemeralExpiration:       proto.Uint32(seconds),
		EphemeralSettingTimestamp: proto.Int64(setAt),
	}})
}

// disappearingText is a text message as a disappearing chat carries it.
func disappearingText(text string, seconds uint32, setAt int64) *waE2E.Message {
	return &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{
		Text: s(text),
		ContextInfo: &waE2E.ContextInfo{
			Expiration:                proto.Uint32(seconds),
			EphemeralSettingTimestamp: proto.Int64(setAt),
		},
	}}
}

func TestTimerReport_supersedes(t *testing.T) {
	cases := []struct {
		name string
		r    timerReport
		cur  timerSetting
		want bool
	}{
		{"a newer setting", timerReport{timerSetting: timerSetting{0, 200}}, timerSetting{week, 100}, true},
		{"an older setting", timerReport{timerSetting: timerSetting{week, 100}}, timerSetting{0, 200}, false},
		{"the same setting again", timerReport{timerSetting: timerSetting{week, 100}}, timerSetting{week, 100}, false},
		{"an untimed report fills in nothing known", timerReport{timerSetting: timerSetting{week, 0}}, timerSetting{}, true},
		{"an untimed report never beats a timed one", timerReport{timerSetting: timerSetting{day, 0}}, timerSetting{week, 100}, false},
		{"the server's current state always stands", timerReport{timerSetting: timerSetting{day, 0}, current: true}, timerSetting{week, 100}, true},
		{"the current state, unchanged", timerReport{timerSetting: timerSetting{week, 0}, current: true}, timerSetting{week, 100}, false},
	}
	for _, c := range cases {
		if got := c.r.supersedes(c.cur); got != c.want {
			t.Errorf("%s: supersedes = %v, want %v", c.name, got, c.want)
		}
	}
}

func TestTimerText(t *testing.T) {
	for seconds, want := range map[uint32]string{
		0:          "Disappearing messages off",
		day:        "Disappearing messages · 24 hours",
		week:       "Disappearing messages · 7 days",
		90 * day:   "Disappearing messages · 90 days",
		2 * day:    "Disappearing messages · 2 days",
		3600:       "Disappearing messages · 1 hour",
		300:        "Disappearing messages · 5 minutes",
		45:         "Disappearing messages · 45 seconds",
		day + 3600: "Disappearing messages · 25 hours",
	} {
		if got := timerText(seconds); got != want {
			t.Errorf("timerText(%d) = %q, want %q", seconds, got, want)
		}
	}
}

// A DM's setting message sets its timer and shows a line, which is neither
// unread nor announced; a setting older than the stored one, delivered
// late, changes nothing.
func TestHandleMessage_settingMessageSetsTheTimer(t *testing.T) {
	b := newFKClientBridge(t)
	events := captureEvents(b)
	now := time.Now().Unix()

	b.handleMessage(settingEvent(alice, alice, "s1", now-60, week, now-60))
	if got := storedTimer(t, b, alice.String()); got != (timerSetting{week, now - 60}) {
		t.Fatalf("timer = %+v", got)
	}
	b.handleMessage(settingEvent(alice, ownJID, "s2", now-30, 0, now-30))
	b.handleMessage(settingEvent(alice, alice, "s0", now-90, day, now-90)) // late

	if got := storedTimer(t, b, alice.String()); got != (timerSetting{0, now - 30}) {
		t.Errorf("timer = %+v, want off as of the newer setting", got)
	}
	want := []string{"Disappearing messages · 7 days", "Disappearing messages off"}
	if got := systemLines(t, b, alice.String()); !equalStrings(got, want) {
		t.Errorf("lines = %q, want %q", got, want)
	}
	if n := unreadCount(t, b, alice.String()); n != 0 {
		t.Errorf("unread = %d: a timer line isn't a message to read", n)
	}
	if n := len(events.ofType("new_message")); n != 0 {
		t.Errorf("%d new_message events for timer lines", n)
	}
	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages WHERE id IN ('s1', 's2', 's0')`); n != 0 {
		t.Errorf("setting messages stored as messages")
	}
	if len(events.ofType("message_updated")) != 2 {
		t.Errorf("message_updated = %v, want one per line", events.ofType("message_updated"))
	}
	if last := lastMessageOf(t, b, alice.String()); last != "timer_"+itoa(now-30)+"_0_"+alice.String() {
		t.Errorf("preview = %q, want the newest line", last)
	}
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }

// Status updates expire on their own schedule; they aren't a chat with a
// timer.
func TestApplyTimer_onlyChats(t *testing.T) {
	b := newFKClientBridge(t)
	now := time.Now().Unix()
	b.handleMessage(messageEvent(types.StatusBroadcastJID, alice, "st1", now, disappearingText("my day", day, now)))
	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages WHERE content_type = 'system'`); n != 0 {
		t.Errorf("a status update added a timer line")
	}
	if got := expiresAt(t, b, "st1"); got != now+day {
		t.Errorf("status expires_at = %d; it still goes when its own timer says", got)
	}
}

// A group's timer is the server's to say; a member's setting message
// doesn't change it.
func TestHandleMessage_groupIgnoresSettingMessages(t *testing.T) {
	b := newFKClientBridge(t)
	now := time.Now().Unix()
	b.handleMessage(settingEvent(grp, alice, "s1", now, week, now))
	if got := b.chatTimer(grp.String()); got != (timerSetting{}) {
		t.Errorf("group timer = %+v from a member's setting message", got)
	}
}

// Every message in a disappearing chat carries the timer: one that reveals
// a change records it, with its line just before the message, and each
// message disappears its timer after it was sent.
func TestHandleMessage_contextInfoCarriesTheTimer(t *testing.T) {
	b := newFKClientBridge(t)
	now := time.Now().Unix()

	b.handleMessage(messageEvent(alice, alice, "m1", now-10, disappearingText("hi", day, now-500)))

	if got := storedTimer(t, b, alice.String()); got != (timerSetting{day, now - 500}) {
		t.Errorf("timer = %+v", got)
	}
	if got := expiresAt(t, b, "m1"); got != now-10+day {
		t.Errorf("expires_at = %d, want sent time + 24h", got)
	}
	var first string
	b.msgDB.QueryRow(`SELECT content_type FROM messages WHERE conversation_jid = ? ORDER BY timestamp, _rowid_ LIMIT 1`, alice.String()).Scan(&first)
	if first != contentSystem {
		t.Errorf("first row is %q, want the timer line before the message", first)
	}

	// Further messages under the same setting add no line.
	b.handleMessage(messageEvent(alice, alice, "m2", now-5, disappearingText("again", day, now-500)))
	if got := systemLines(t, b, alice.String()); len(got) != 1 {
		t.Errorf("lines = %q, want one", got)
	}
	// A message without a timer, in a chat whose timer is off, never expires.
	b.handleMessage(textEvent(bob, bob, "m3", "plain", now))
	if got := expiresAt(t, b, "m3"); got != 0 {
		t.Errorf("plain message expires_at = %d", got)
	}
	if got := b.chatTimer(bob.String()); got != (timerSetting{}) {
		t.Errorf("a plain message set a timer: %+v", got)
	}
}

// A message whose timer ran out before it was delivered (Photon was offline)
// isn't stored or announced.
func TestHandleMessage_alreadyDisappeared(t *testing.T) {
	b := newFKClientBridge(t)
	events := captureEvents(b)
	now := time.Now().Unix()
	b.handleMessage(messageEvent(alice, alice, "old", now-2*day, disappearingText("gone", day, now-3*day)))

	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages WHERE id = 'old'`); n != 0 {
		t.Errorf("a disappeared message was stored")
	}
	if n := len(events.ofType("new_message")); n != 0 {
		t.Errorf("a disappeared message was announced")
	}
	if got := b.chatTimer(alice.String()).seconds; got != day {
		t.Errorf("timer = %d; the message still tells the chat's timer", got)
	}
}

func TestHandleGroupInfo_ephemeralChange(t *testing.T) {
	b := newFKClientBridge(t)
	now := time.Now()
	b.HandleEvent(&events.GroupInfo{JID: grp, Timestamp: now, Ephemeral: &types.GroupEphemeral{IsEphemeral: true, DisappearingTimer: 90 * day}})
	if got := storedTimer(t, b, grp.String()); got != (timerSetting{90 * day, now.Unix()}) {
		t.Errorf("timer = %+v", got)
	}
	b.HandleEvent(&events.GroupInfo{JID: grp, Timestamp: now.Add(time.Minute), Ephemeral: &types.GroupEphemeral{IsEphemeral: false}})
	if got := b.chatTimer(grp.String()).seconds; got != 0 {
		t.Errorf("timer = %d after it was turned off", got)
	}
	want := []string{"Disappearing messages · 90 days", "Disappearing messages off"}
	if got := systemLines(t, b, grp.String()); !equalStrings(got, want) {
		t.Errorf("lines = %q, want %q", got, want)
	}
}

// Joining a group (or fetching its info) gives its current timer, which
// stands whatever was stored before.
func TestGroupTimerFromInfo(t *testing.T) {
	b := newFKClientBridge(t)
	now := time.Now()
	b.HandleEvent(&events.GroupInfo{JID: grp, Timestamp: now, Ephemeral: &types.GroupEphemeral{IsEphemeral: true, DisappearingTimer: week}})

	b.HandleEvent(&events.JoinedGroup{GroupInfo: types.GroupInfo{JID: grp, GroupEphemeral: types.GroupEphemeral{IsEphemeral: true, DisappearingTimer: day}}})
	if got := b.chatTimer(grp.String()).seconds; got != day {
		t.Errorf("timer = %d, want the group's current one", got)
	}
	b.applyGroupTimer(grp, &types.GroupInfo{JID: grp})
	if got := b.chatTimer(grp.String()).seconds; got != 0 {
		t.Errorf("timer = %d, want off as the server has it", got)
	}
}

func TestHandleHistorySync_timers(t *testing.T) {
	b := newFKClientBridge(t)
	b.retention = RetentionConfig{MaxMessages: 50}
	now := time.Now().Unix()
	carol := types.NewJID("447700900003", types.DefaultUserServer)
	dave := types.NewJID("447700900004", types.DefaultUserServer)

	expiring := historyMsg(alice, "h1", false, "", uint64(now-100), disappearingText("soon", week, now-day))
	expired := historyMsg(alice, "h2", false, "", uint64(now-2*week), disappearingText("gone", week, now-3*week))
	recorded := historyMsg(carol, "h3", false, "", uint64(now-100), &waE2E.Message{Conversation: s("payload says")})
	recorded.Message.EphemeralExpirationTimestamp = proto.Uint64(uint64(now + 3600))

	b.handleHistorySync(&events.HistorySync{Data: &waHistorySync.HistorySync{
		Conversations: []*waHistorySync.Conversation{
			{
				ID:                        s(alice.String()),
				EphemeralExpiration:       proto.Uint32(week),
				EphemeralSettingTimestamp: proto.Int64(now - day),
				Messages:                  []*waHistorySync.HistorySyncMsg{expiring, expired},
			},
			{ID: s(carol.String()), Messages: []*waHistorySync.HistorySyncMsg{recorded}},
			{
				// No timer on the conversation: its newest message tells.
				ID: s(dave.String()),
				Messages: []*waHistorySync.HistorySyncMsg{
					historyMsg(dave, "h4", false, "", uint64(now-50), disappearingText("x", day, 0)),
					// A setting message in the payload.
					historyMsg(dave, "h5", false, "", uint64(now-60), &waE2E.Message{ProtocolMessage: &waE2E.ProtocolMessage{
						Type:                waE2E.ProtocolMessage_EPHEMERAL_SETTING.Enum(),
						EphemeralExpiration: proto.Uint32(day),
					}}),
				},
			},
		},
	}})

	if got := storedTimer(t, b, alice.String()); got != (timerSetting{week, now - day}) {
		t.Errorf("alice's timer = %+v", got)
	}
	if got := systemLines(t, b, alice.String()); !equalStrings(got, []string{"Disappearing messages · 7 days"}) {
		t.Errorf("alice's lines = %q", got)
	}
	if got := expiresAt(t, b, "h1"); got != now-100+week {
		t.Errorf("h1 expires_at = %d", got)
	}
	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages WHERE id = 'h2'`); n != 0 {
		t.Errorf("a history message that had already disappeared was stored")
	}
	if got := expiresAt(t, b, "h3"); got != now+3600 {
		t.Errorf("h3 expires_at = %d, want the payload's", got)
	}
	if got := b.chatTimer(dave.String()).seconds; got != day {
		t.Errorf("dave's timer = %d, want the newest message's", got)
	}
}

// fakeTimerChat stores a chat with a timer, as if WhatsApp had told it.
func fakeTimerChat(t *testing.T, b *Bridge, jid string, seconds uint32, setAt int64) {
	t.Helper()
	if !b.applyTimer(jid, timerReport{timerSetting: timerSetting{seconds, setAt}}) {
		t.Fatalf("timer of %s not stored", jid)
	}
}

// Every message Photon sends to a disappearing chat carries the chat's
// timer, so it disappears for everyone, and its row disappears with it.
func TestSends_carryTheChatTimer(t *testing.T) {
	b, fake, path := mediaBridge(t, "send_media_1.pdf")
	ctx := context.Background()
	const setAt = 1_800_000_000
	fakeTimerChat(t, b, alice.String(), week, setAt)

	textID, _, err := b.SendTextMessage(ctx, alice.String(), "hello", "")
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := b.SendTextMessage(ctx, alice.String(), "re", "m0"); err != nil {
		t.Fatal(err)
	}
	mediaID, _ := sendMedia(t, b, SendMediaPayload{JID: alice.String(), FilePath: path, MimeType: "application/pdf", FileName: "a.pdf"})
	b.handleRequest(ctx, Envelope{ID: "e", Type: "edit_message", Payload: mustMarshal(EditMessagePayload{JID: alice.String(), MessageID: textID, NewText: "hello!"})})

	if len(fake.sent) != 4 {
		t.Fatalf("sent %d messages, want 4", len(fake.sent))
	}
	contexts := map[string]*waE2E.ContextInfo{
		"text":  fake.sent[0].GetExtendedTextMessage().GetContextInfo(),
		"reply": fake.sent[1].GetExtendedTextMessage().GetContextInfo(),
		"media": fake.sent[2].GetDocumentMessage().GetContextInfo(),
		"edit":  fake.sent[3].GetEditedMessage().GetMessage().GetProtocolMessage().GetEditedMessage().GetExtendedTextMessage().GetContextInfo(),
	}
	for kind, c := range contexts {
		if c.GetExpiration() != week || c.GetEphemeralSettingTimestamp() != setAt {
			t.Errorf("%s carries expiration %d, setting time %d; want the chat's", kind, c.GetExpiration(), c.GetEphemeralSettingTimestamp())
		}
	}
	if got := contexts["reply"].GetStanzaID(); got != "m0" {
		t.Errorf("the reply lost what it replies to: %q", got)
	}
	// The fake's server time is 1_900_000_000.
	for _, id := range []string{textID, mediaID} {
		if got := expiresAt(t, b, id); got != 1_900_000_000+week {
			t.Errorf("%s expires_at = %d, want the server's send time + 7 days", id, got)
		}
	}
	if got := b.chatTimer(alice.String()); got != (timerSetting{week, setAt}) {
		t.Errorf("sending changed the timer: %+v", got)
	}
}

// In a chat without a timer, sends are as before: plain text, no expiry.
func TestSends_withoutATimer(t *testing.T) {
	b, fake, _ := mediaBridge(t, "send_media_1.pdf")
	id, _, err := b.SendTextMessage(context.Background(), bob.String(), "hello", "")
	if err != nil {
		t.Fatal(err)
	}
	if fake.sent[0].GetConversation() != "hello" || fake.sent[0].ExtendedTextMessage != nil {
		t.Errorf("sent %v, want a plain text", fake.sent[0])
	}
	if got := expiresAt(t, b, id); got != 0 {
		t.Errorf("expires_at = %d", got)
	}
}

// A retry goes out under the chat's timer as it is now.
func TestRetryMessage_carriesTheTimer(t *testing.T) {
	b, fake, _ := mediaBridge(t, "send_media_1.pdf")
	fake.offline = true
	id, _, _ := b.SendTextMessage(context.Background(), alice.String(), "hello", "")
	fakeTimerChat(t, b, alice.String(), day, 0)
	fake.offline = false

	if err := b.RetryMessage(context.Background(), id); err != nil {
		t.Fatal(err)
	}
	if got := fake.sent[0].GetExtendedTextMessage().GetContextInfo().GetExpiration(); got != day {
		t.Errorf("retry expiration = %d, want %d", got, day)
	}
	if c := fake.sent[0].GetExtendedTextMessage().GetContextInfo(); c.EphemeralSettingTimestamp != nil {
		t.Errorf("an unknown setting time was sent as %d", c.GetEphemeralSettingTimestamp())
	}
	if got := expiresAt(t, b, id); got != 1_900_000_000+day {
		t.Errorf("expires_at = %d", got)
	}
}

func TestExpireMessages(t *testing.T) {
	b := newFKClientBridge(t)
	b.dataDir = t.TempDir()
	events := captureEvents(b)
	now := time.Now().Unix()
	os.MkdirAll(filepath.Join(b.dataDir, "media"), 0755)
	media := filepath.Join(b.dataDir, "media", "m1.jpg")
	os.WriteFile(media, []byte("jpeg"), 0644)

	// m1 (a photo) and m2 go first; m3 stays. All unread.
	b.handleMessage(messageEvent(alice, alice, "m1", now-100, &waE2E.Message{ImageMessage: &waE2E.ImageMessage{
		ContextInfo: &waE2E.ContextInfo{Expiration: proto.Uint32(day)},
	}}))
	b.UpdateMediaURL("m1", media)
	b.UpsertReaction("m1", ownJID.String(), "👍", now)
	b.handleMessage(messageEvent(alice, alice, "m2", now-90, disappearingText("two", day, 0)))
	b.handleMessage(messageEvent(alice, alice, "m3", now-80, disappearingText("three", week, 0)))

	b.expireMessages(now + day - 95)
	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages WHERE id = 'm1'`); n != 0 {
		t.Fatalf("m1 wasn't deleted")
	}
	if got := events.ofType("messages_removed"); len(got) != 1 || got[0]["was_unread"] != true || got[0]["unread_count"] != float64(2) {
		t.Errorf("messages_removed = %v", got)
	} else if got[0]["newest_unread"] != nil {
		t.Errorf("newest_unread set though the newest unread message is still there: %v", got[0])
	}

	b.expireMessages(now + day)
	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages WHERE conversation_jid = ? AND content_type <> 'system'`, alice.String()); n != 1 {
		t.Errorf("%d messages left, want m3", n)
	}
	if n := rowCount(t, b, `SELECT COUNT(*) FROM reactions WHERE message_id = 'm1'`); n != 0 {
		t.Errorf("reactions of a disappeared message are left")
	}
	if _, err := os.Stat(media); !os.IsNotExist(err) {
		t.Errorf("the disappeared photo is still on disk")
	}
	if got := unreadCount(t, b, alice.String()); got != 1 {
		t.Errorf("unread = %d, want 1 (m3)", got)
	}
	if last := lastMessageOf(t, b, alice.String()); last != "m3" {
		t.Errorf("preview = %q", last)
	}
}

// When the newest unread message disappears, the notification that showed
// it should show the newest one left — or go, when none is.
func TestExpireMessages_notificationFollowsTheNewestUnread(t *testing.T) {
	b := newFKClientBridge(t)
	events := captureEvents(b)
	now := time.Now().Unix()
	// The timer was shortened: the newer message goes first.
	b.handleMessage(messageEvent(alice, alice, "m1", now-100, disappearingText("older", week, 0)))
	b.handleMessage(messageEvent(alice, alice, "m2", now-90, disappearingText("newer", 3600, now-95)))

	b.expireMessages(now + 3600)
	got := events.ofType("messages_removed")
	if len(got) != 1 {
		t.Fatalf("messages_removed = %v", got)
	}
	newest, _ := got[0]["newest_unread"].(map[string]any)
	if newest["message_id"] != "m1" || newest["text_body"] != "older" || newest["conversation_jid"] != alice.String() {
		t.Errorf("newest_unread = %v, want m1", got[0]["newest_unread"])
	}

	b.expireMessages(now + week)
	got = events.ofType("messages_removed")
	if last := got[len(got)-1]; last["unread_count"] != float64(0) || last["was_unread"] != true || last["newest_unread"] != nil {
		t.Errorf("last messages_removed = %v, want unread 0", last)
	}
}

// A chat whose messages have all disappeared keeps its row, and so its
// timer: Photon's next message to it must still disappear.
func TestPruneMessages_keepsAChatsTimer(t *testing.T) {
	b := newFKClientBridge(t)
	b.retention = RetentionConfig{MaxDays: 7}
	now := time.Now().Unix()
	b.handleMessage(messageEvent(alice, alice, "m1", now-100, disappearingText("hi", day, now-200)))
	b.handleMessage(textEvent(bob, bob, "m2", "hi", now-100))
	b.msgDB.Exec(`DELETE FROM messages`)

	b.pruneMessages()
	if got := b.chatTimer(alice.String()).seconds; got != day {
		t.Errorf("timer = %d after the chat emptied", got)
	}
	if n := rowCount(t, b, `SELECT COUNT(*) FROM conversations WHERE jid = ?`, bob.String()); n != 0 {
		t.Errorf("an empty chat with nothing to keep wasn't removed")
	}
}

func TestWithoutCallRows_dropsSystemLines(t *testing.T) {
	b := newFKClientBridge(t)
	now := time.Now().Unix()
	b.handleMessage(settingEvent(alice, alice, "s1", now, day, now))
	b.handleMessage(textEvent(alice, alice, "m1", "hi", now))
	line := "timer_" + itoa(now) + "_86400_" + alice.String()
	if got := b.WithoutCallRows([]string{"m1", line}); !equalStrings(got, []string{"m1"}) {
		t.Errorf("receipt ids = %q", got)
	}
}

// A chat's @lid row merged into its phone row brings the timer set later.
func TestMergeConversation_keepsTheNewerTimer(t *testing.T) {
	b := newFKClientBridge(t)
	lid := types.NewJID("11223344", types.HiddenUserServer).String()
	fakeTimerChat(t, b, alice.String(), day, 100)
	fakeTimerChat(t, b, lid, week, 200)
	if _, err := b.mergeConversation(lid, alice.String()); err != nil {
		t.Fatal(err)
	}
	if got := b.chatTimer(alice.String()); got != (timerSetting{week, 200}) {
		t.Errorf("timer = %+v", got)
	}
}

// v3Database is messages.db as 0.4.0 left it (schema version 3): no timers,
// no expiries.
func v3Database(t *testing.T) *Bridge {
	t.Helper()
	registerFKDriver()
	db, err := sql.Open("sqlite_fk", messageDBDSN(t.TempDir()))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	for _, stmt := range []string{
		`CREATE TABLE conversations (jid TEXT PRIMARY KEY, name TEXT, is_group INTEGER NOT NULL DEFAULT 0,
			last_message_id TEXT, last_timestamp INTEGER NOT NULL DEFAULT 0, unread_count INTEGER NOT NULL DEFAULT 0,
			is_muted INTEGER NOT NULL DEFAULT 0, avatar_url TEXT, updated_at INTEGER NOT NULL DEFAULT 0,
			muted_until INTEGER NOT NULL DEFAULT 0, name_rank INTEGER NOT NULL DEFAULT 0)`,
		`CREATE TABLE messages (id TEXT PRIMARY KEY, conversation_jid TEXT NOT NULL, sender_jid TEXT NOT NULL,
			timestamp INTEGER NOT NULL, content_type TEXT NOT NULL, text_body TEXT, media_url TEXT, media_mime TEXT,
			media_size INTEGER, thumbnail_path TEXT, sticker_pack_id TEXT, reply_to_id TEXT,
			edit_version INTEGER NOT NULL DEFAULT 0, is_from_me INTEGER NOT NULL DEFAULT 0,
			status TEXT NOT NULL DEFAULT 'sent', raw_proto BLOB,
			FOREIGN KEY (conversation_jid) REFERENCES conversations(jid))`,
		`PRAGMA user_version = 3`,
	} {
		if _, err := db.Exec(stmt); err != nil {
			t.Fatal(err)
		}
	}
	return &Bridge{msgDB: db, log: waLog.Noop, groupNames: make(map[string]cachedGroupName)}
}

func v3Message(t *testing.T, b *Bridge, id, chat string, ts int64, msg *waE2E.Message) {
	t.Helper()
	var raw []byte
	if msg != nil {
		raw, _ = proto.Marshal(msg)
	}
	b.msgDB.Exec(`INSERT OR IGNORE INTO conversations (jid) VALUES (?)`, chat)
	if _, err := b.msgDB.Exec(`INSERT INTO messages (id, conversation_jid, sender_jid, timestamp, content_type, raw_proto)
		VALUES (?, ?, ?, ?, 'text', ?)`, id, chat, chat, ts, raw); err != nil {
		t.Fatal(err)
	}
}

// Upgrading keeps every row and works out the timers 0.4.0 ignored from the
// protos it stored: each message's expiry, and each chat's timer from its
// newest message.
func TestMigrateDisappearing(t *testing.T) {
	b := v3Database(t)
	now := time.Now().Unix()
	off := types.NewJID("447700900005", types.DefaultUserServer).String()
	v3Message(t, b, "a1", alice.String(), now-50, disappearingText("x", week, now-day))
	v3Message(t, b, "a2", alice.String(), now-40, nil) // sent by Photon: no proto
	v3Message(t, b, "b1", off, now-50, disappearingText("x", week, now-day))
	v3Message(t, b, "b2", off, now-40, &waE2E.Message{Conversation: s("timer off since")})

	if err := b.InitMessageDB(context.Background()); err != nil {
		t.Fatal(err)
	}
	if v := rowCount(t, b, `PRAGMA user_version`); v != len(migrations) {
		t.Errorf("user_version = %d", v)
	}
	if got := expiresAt(t, b, "a1"); got != now-50+week {
		t.Errorf("a1 expires_at = %d", got)
	}
	if got := expiresAt(t, b, "a2"); got != 0 {
		t.Errorf("a2 expires_at = %d", got)
	}
	if got := storedTimer(t, b, alice.String()); got != (timerSetting{week, now - day}) {
		t.Errorf("alice's timer = %+v", got)
	}
	if got := b.chatTimer(off).seconds; got != 0 {
		t.Errorf("timer = %d, though the chat's newest message has none", got)
	}
	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages WHERE content_type = 'system'`); n != 0 {
		t.Errorf("the migration added %d timer lines", n)
	}
	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages`); n != 4 {
		t.Errorf("rows = %d, want all 4 kept", n)
	}
}

func equalStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
