package main

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"go.mau.fi/whatsmeow/proto/waCommon"
	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

func undecryptable(chat, sender types.JID, id string, ts int64) *events.UndecryptableMessage {
	return &events.UndecryptableMessage{Info: messageEvent(chat, sender, id, ts, nil).Info}
}

func contentOf(t *testing.T, b *Bridge, id string) (contentType, text string) {
	t.Helper()
	if err := b.msgDB.QueryRow(`SELECT content_type, COALESCE(text_body, '') FROM messages WHERE id = ?`, id).Scan(&contentType, &text); err != nil {
		t.Fatalf("message %s: %v", id, err)
	}
	return
}

// A message that couldn't be decrypted shows as unavailable, counted and
// announced once however often the failure repeats.
func TestUndecryptable_storedAsUnavailable(t *testing.T) {
	b := newFKClientBridge(t)
	events := captureEvents(b)
	now := time.Now().Unix()

	b.HandleEvent(undecryptable(alice, alice, "u1", now))
	b.HandleEvent(undecryptable(alice, alice, "u1", now)) // the resend failed too

	if ct, text := contentOf(t, b, "u1"); ct != contentUnavailable || text != "" {
		t.Errorf("stored %q %q", ct, text)
	}
	if got := unreadCount(t, b, alice.String()); got != 1 {
		t.Errorf("unread = %d, want 1", got)
	}
	got := events.ofType("new_message")
	if len(got) != 1 || got[0]["content_type"] != contentUnavailable || got[0]["message_id"] != "u1" {
		t.Errorf("new_message = %v", got)
	}
}

// What the sender marked to hide on failure (reactions, edits…) and status
// updates aren't shown.
func TestUndecryptable_hiddenAndNotChats(t *testing.T) {
	b := newFKClientBridge(t)
	now := time.Now().Unix()
	hidden := undecryptable(alice, alice, "h1", now)
	hidden.DecryptFailMode = events.DecryptFailHide
	b.HandleEvent(hidden)
	b.HandleEvent(undecryptable(types.StatusBroadcastJID, alice, "st1", now))

	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages`); n != 0 {
		t.Errorf("stored %d placeholders", n)
	}
}

// WhatsApp never gives view-once media to linked devices; the notice is a
// VIEW_ONCE row naming the kind of media, when the stanza says.
func TestUndecryptable_viewOnce(t *testing.T) {
	b := newFKClientBridge(t)
	announced := captureEvents(b)
	now := time.Now().Unix()
	for id, mediaType := range map[string]string{"v1": "image", "v2": "video", "v3": "ptt", "v4": ""} {
		evt := undecryptable(alice, alice, id, now)
		evt.IsUnavailable, evt.UnavailableType = true, events.UnavailableTypeViewOnce
		evt.Info.MediaType = mediaType
		b.HandleEvent(evt)
	}
	for id, want := range map[string]string{"v1": "photo", "v2": "video", "v3": "voice", "v4": ""} {
		if ct, text := contentOf(t, b, id); ct != contentViewOnce || text != want {
			t.Errorf("%s stored %q %q, want view_once %q", id, ct, text, want)
		}
	}
	if got := unreadCount(t, b, alice.String()); got != 4 {
		t.Errorf("unread = %d: view-once media counts as unread", got)
	}
	if got := len(announced.ofType("new_message")); got != 4 {
		t.Errorf("new_message = %d", got)
	}
}

// The sender's resend replaces the placeholder in place: no second row, no
// second unread, and the notification shows the message without alerting.
func TestUndecryptable_resendReplacesThePlaceholder(t *testing.T) {
	b := newFKClientBridge(t)
	events := captureEvents(b)
	now := time.Now().Unix()
	b.HandleEvent(undecryptable(alice, alice, "u1", now))

	b.HandleEvent(textEvent(alice, alice, "u1", "here it is", now))

	if ct, text := contentOf(t, b, "u1"); ct != "text" || text != "here it is" {
		t.Errorf("stored %q %q", ct, text)
	}
	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages`); n != 1 {
		t.Errorf("%d rows, want the placeholder replaced", n)
	}
	if got := unreadCount(t, b, alice.String()); got != 1 {
		t.Errorf("unread = %d, want 1", got)
	}
	if got := len(events.ofType("new_message")); got != 1 {
		t.Errorf("new_message = %d, want only the placeholder's", got)
	}
	got := events.ofType("message_replaced")
	if len(got) != 1 || got[0]["text_body"] != "here it is" || got[0]["newest_unread"] != true {
		t.Errorf("message_replaced = %v", got)
	}

	// A redelivery of the real message changes nothing more.
	b.HandleEvent(textEvent(alice, alice, "u1", "here it is", now))
	if got := len(events.ofType("message_replaced")); got != 1 {
		t.Errorf("message_replaced = %d after a redelivery", got)
	}
}

// Message IDs are visible to every group member: only the sender's message
// replaces their placeholder.
func TestUndecryptable_onlyTheSenderReplaces(t *testing.T) {
	b := newFKClientBridge(t)
	now := time.Now().Unix()
	b.HandleEvent(undecryptable(grp, alice, "u1", now))
	b.HandleEvent(textEvent(grp, bob, "u1", "spoofed", now))

	if ct, _ := contentOf(t, b, "u1"); ct != contentUnavailable {
		t.Errorf("another member's message replaced the placeholder (%q)", ct)
	}
	b.HandleEvent(textEvent(grp, alice, "u1", "real", now))
	if ct, text := contentOf(t, b, "u1"); ct != "text" || text != "real" {
		t.Errorf("the sender's resend stored %q %q", ct, text)
	}
}

// A resend that turns out to have nothing to show (here a reaction) takes
// the placeholder away, and its notification with it.
func TestUndecryptable_resendWithNothingToShow(t *testing.T) {
	b := newFKClientBridge(t)
	events := captureEvents(b)
	now := time.Now().Unix()
	b.HandleEvent(textEvent(alice, alice, "m1", "hi", now-10))
	b.MarkChatReadUpTo(alice.String(), now)
	b.HandleEvent(undecryptable(alice, alice, "u1", now))

	b.HandleEvent(messageEvent(alice, alice, "u1", now, &waE2E.Message{ReactionMessage: &waE2E.ReactionMessage{
		Key: &waCommon.MessageKey{ID: s("m1")}, Text: s("👍"),
	}}))

	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages WHERE id = 'u1'`); n != 0 {
		t.Errorf("the placeholder is still there")
	}
	if got := unreadCount(t, b, alice.String()); got != 0 {
		t.Errorf("unread = %d", got)
	}
	got := events.ofType("messages_removed")
	if len(got) != 1 || got[0]["reason"] != "placeholder" || got[0]["unread_count"] != float64(0) || got[0]["was_unread"] != true {
		t.Errorf("messages_removed = %v", got)
	}
}

// The phone's copy of a placeholder, asked for after a decryption failure.
func TestUndecryptable_phoneResend(t *testing.T) {
	b := newFKClientBridge(t)
	now := time.Now().Unix()
	b.HandleEvent(undecryptable(alice, alice, "u1", now))
	resend := textEvent(alice, alice, "u1", "from the phone", now)
	resend.UnavailableRequestID = "req1"
	b.HandleEvent(resend)
	if _, text := contentOf(t, b, "u1"); text != "from the phone" {
		t.Errorf("text = %q", text)
	}
}

// A placeholder in a disappearing chat disappears with the chat's timer.
func TestUndecryptable_disappearsWithTheChat(t *testing.T) {
	b := newFKClientBridge(t)
	now := time.Now().Unix()
	fakeTimerChat(t, b, alice.String(), day, 0)
	b.HandleEvent(undecryptable(alice, alice, "u1", now))
	if got := expiresAt(t, b, "u1"); got != now+day {
		t.Errorf("expires_at = %d", got)
	}
}

func viewOnceImage() *waE2E.Message {
	return &waE2E.Message{ImageMessage: &waE2E.ImageMessage{
		Mimetype:      s("image/jpeg"),
		Caption:       s("don't keep this"),
		JPEGThumbnail: []byte("preview"),
		MediaKey:      []byte("key"),
	}}
}

// View-once media that does arrive is a VIEW_ONCE notice: unread and
// announced, but with nothing kept that could show or fetch it.
func TestHandleMessage_viewOnceIsNeverKept(t *testing.T) {
	b := newFKClientBridge(t)
	b.dataDir = t.TempDir()
	os.MkdirAll(filepath.Join(b.dataDir, "thumbs"), 0755)
	events := captureEvents(b)
	now := time.Now().Unix()

	wrapped := messageEvent(alice, alice, "v1", now, viewOnceImage())
	wrapped.IsViewOnce = true
	b.HandleEvent(wrapped)
	flagged := viewOnceImage()
	flagged.ImageMessage.ViewOnce = proto.Bool(true)
	b.HandleEvent(messageEvent(alice, alice, "v2", now, flagged))
	voice := messageEvent(alice, alice, "v3", now, &waE2E.Message{AudioMessage: &waE2E.AudioMessage{PTT: proto.Bool(true)}})
	voice.IsViewOnce = true
	b.HandleEvent(voice)

	for id, want := range map[string]string{"v1": "photo", "v2": "photo", "v3": "voice"} {
		if ct, text := contentOf(t, b, id); ct != contentViewOnce || text != want {
			t.Errorf("%s stored %q %q", id, ct, text)
		}
	}
	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages WHERE raw_proto IS NOT NULL OR media_mime IS NOT NULL OR thumbnail_path IS NOT NULL`); n != 0 {
		t.Errorf("%d view-once rows kept their media's proto, type or preview", n)
	}
	if entries, _ := os.ReadDir(filepath.Join(b.dataDir, "thumbs")); len(entries) != 0 {
		t.Errorf("a view-once preview was saved")
	}
	if _, err := b.DownloadMediaByID("v1"); err == nil {
		t.Errorf("view-once media was offered for download")
	}
	if got := unreadCount(t, b, alice.String()); got != 3 {
		t.Errorf("unread = %d", got)
	}
	if got := events.ofType("new_message"); len(got) != 3 || got[0]["content_type"] != contentViewOnce || got[0]["text_body"] != "photo" {
		t.Errorf("new_message = %v", got)
	}
}

func TestHandleHistorySync_viewOnce(t *testing.T) {
	b := newFKClientBridge(t)
	b.retention = RetentionConfig{MaxMessages: 50}
	now := uint64(time.Now().Unix())
	b.handleHistorySync(&events.HistorySync{Data: &waHistorySync.HistorySync{
		Conversations: []*waHistorySync.Conversation{{
			ID: s(alice.String()),
			Messages: []*waHistorySync.HistorySyncMsg{
				historyMsg(alice, "h1", false, "", now, &waE2E.Message{ViewOnceMessageV2: wrap(viewOnceImage())}),
			},
		}},
	}})
	if ct, text := contentOf(t, b, "h1"); ct != contentViewOnce || text != "photo" {
		t.Errorf("stored %q %q", ct, text)
	}
}

// Upgrading turns the view-once media 0.4.0 stored as ordinary photos into
// notices, and deletes what it downloaded.
func TestMigrateViewOnce(t *testing.T) {
	b := v3Database(t)
	b.dataDir = t.TempDir()
	os.MkdirAll(filepath.Join(b.dataDir, "media"), 0755)
	media := filepath.Join(b.dataDir, "media", "v1.jpg")
	os.WriteFile(media, []byte("jpeg"), 0644)
	flagged := viewOnceImage()
	flagged.ImageMessage.ViewOnce = proto.Bool(true)
	v3Message(t, b, "v1", alice.String(), 100, flagged)
	v3Message(t, b, "p1", alice.String(), 101, viewOnceImage())
	v3Message(t, b, "own", alice.String(), 102, flagged) // sent from the user's phone
	v3Message(t, b, "r1", alice.String(), 103, &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{
		Text: s("that one"), ContextInfo: &waE2E.ContextInfo{StanzaID: s("v1"), QuotedMessage: flagged},
	}})
	b.msgDB.Exec(`UPDATE messages SET content_type = 'image', media_url = ? WHERE id = 'v1'`, media)
	b.msgDB.Exec(`UPDATE messages SET content_type = 'image' WHERE id IN ('p1', 'own')`)
	b.msgDB.Exec(`UPDATE messages SET is_from_me = 1 WHERE id = 'own'`)

	if err := b.InitMessageDB(context.Background()); err != nil {
		t.Fatal(err)
	}
	if ct, text := contentOf(t, b, "v1"); ct != contentViewOnce || text != "photo" {
		t.Errorf("v1 stored %q %q", ct, text)
	}
	if ct, _ := contentOf(t, b, "p1"); ct != "image" {
		t.Errorf("an ordinary photo became %q", ct)
	}
	if _, err := os.Stat(media); !os.IsNotExist(err) {
		t.Errorf("the downloaded view-once photo is still on disk")
	}
	if ct, _ := contentOf(t, b, "own"); ct != contentViewOnce {
		t.Errorf("the user's own view-once photo stayed %q", ct)
	}
	raw, _ := b.GetMessageRawProto("r1")
	var reply waE2E.Message
	proto.Unmarshal(raw, &reply)
	if getContextInfo(&reply).GetQuotedMessage() != nil || getContextInfo(&reply).GetStanzaID() != "v1" {
		t.Errorf("a stored reply still holds its quoted copy")
	}
}

// A placeholder stored under a DM's LID (its number unknown then) is still
// replaced by a resend that resolves to the phone JID.
func TestUndecryptable_resendUnderTheOtherJID(t *testing.T) {
	b := newFKClientBridge(t)
	now := time.Now().Unix()
	lid := types.NewJID("11223344", types.HiddenUserServer)
	b.HandleEvent(undecryptable(lid, lid, "u1", now))
	b.client.Store.LIDs.PutLIDMapping(context.Background(), lid, alice)

	b.HandleEvent(textEvent(lid, lid, "u1", "here it is", now))
	if ct, text := contentOf(t, b, "u1"); ct != "text" || text != "here it is" {
		t.Errorf("stored %q %q", ct, text)
	}
}

// View-once media stays a notice even if a copy without its view-once
// markers turns up (from the phone, say).
func TestUndecryptable_viewOnceIsNeverUpgraded(t *testing.T) {
	b := newFKClientBridge(t)
	now := time.Now().Unix()
	evt := undecryptable(alice, alice, "v1", now)
	evt.IsUnavailable, evt.UnavailableType = true, events.UnavailableTypeViewOnce
	b.HandleEvent(evt)

	resend := messageEvent(alice, alice, "v1", now, viewOnceImage())
	resend.UnavailableRequestID = "req1"
	b.HandleEvent(resend)
	if ct, _ := contentOf(t, b, "v1"); ct != contentViewOnce {
		t.Errorf("stored %q", ct)
	}
	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages WHERE raw_proto IS NOT NULL`); n != 0 {
		t.Errorf("the media's keys were stored")
	}
}

// A resend that doesn't disappear doesn't take the chat timer the
// placeholder guessed.
func TestUndecryptable_resendKeepsItsOwnExpiry(t *testing.T) {
	b := newFKClientBridge(t)
	now := time.Now().Unix()
	fakeTimerChat(t, b, alice.String(), day, 0)
	b.HandleEvent(undecryptable(alice, alice, "u1", now))
	b.HandleEvent(textEvent(alice, alice, "u1", "kept", now))
	if got := expiresAt(t, b, "u1"); got != 0 {
		t.Errorf("expires_at = %d", got)
	}
}
