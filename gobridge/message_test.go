package main

import (
	"math"
	"os"
	"path/filepath"
	"testing"
	"time"

	"go.mau.fi/whatsmeow/proto/waCommon"
	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

func TestHandleMessage_redeliveryCountsOnce(t *testing.T) {
	b := newClientBridge(t)
	events := captureEvents(b)
	alice := types.NewJID("447700900001", types.DefaultUserServer)

	evt := textEvent(alice, alice, "m1", "hello", 100)
	b.handleMessage(evt)
	// The same stanza again (offline replay / retry) must be a no-op.
	b.handleMessage(textEvent(alice, alice, "m1", "hello", 100))

	if got := unreadCount(t, b, alice.String()); got != 1 {
		t.Errorf("unread = %d, want 1 after a redelivery", got)
	}
	if got := len(events.ofType("new_message")); got != 1 {
		t.Errorf("new_message broadcasts = %d, want 1", got)
	}
}

func revokeEvent(chat, sender types.JID, id, targetID string, ts int64) *events.Message {
	return messageEvent(chat, sender, id, ts, &waE2E.Message{
		ProtocolMessage: &waE2E.ProtocolMessage{
			Type: waE2E.ProtocolMessage_REVOKE.Enum(),
			Key:  &waCommon.MessageKey{ID: proto.String(targetID)},
		},
	})
}

func TestHandleMessage_revokeBlanksUnreadMessage(t *testing.T) {
	b := newClientBridge(t)
	b.dataDir = t.TempDir()
	events := captureEvents(b)
	alice := types.NewJID("447700900001", types.DefaultUserServer)

	b.handleMessage(textEvent(alice, alice, "m1", "regrettable", 100))
	b.UpsertReaction("m1", ownJID.String(), "😂", 101)
	b.handleMessage(revokeEvent(alice, alice, "r1", "m1", 102))

	if got := textBody(t, b, "m1"); got != "" {
		t.Errorf("revoked text = %q, want blank", got)
	}
	if got := messageStatus(t, b, "m1"); got != "deleted" {
		t.Errorf("revoked status = %q, want deleted", got)
	}
	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages WHERE id = 'r1'`); n != 0 {
		t.Errorf("the revoke itself was stored as a message")
	}
	if n := rowCount(t, b, `SELECT COUNT(*) FROM reactions WHERE message_id = 'm1'`); n != 0 {
		t.Errorf("reactions on a revoked message survived")
	}
	if got := unreadCount(t, b, alice.String()); got != 0 {
		t.Errorf("unread = %d, want 0 (the only unread message was deleted)", got)
	}
	got := events.ofType("message_revoked")
	if len(got) != 1 || got[0]["was_unread"] != true || got[0]["unread_count"] != float64(0) {
		t.Errorf("message_revoked = %v, want one with was_unread and unread_count 0", got)
	}
	if len(events.ofType("message_updated")) != 1 {
		t.Errorf("no message_updated refresh for the revoke")
	}
}

func TestHandleMessage_revokeOfReadMessageKeepsUnread(t *testing.T) {
	b := newClientBridge(t)
	b.dataDir = t.TempDir()
	alice := types.NewJID("447700900001", types.DefaultUserServer)

	b.handleMessage(textEvent(alice, alice, "m1", "old", 100))
	b.MarkChatReadUpTo(alice.String(), math.MaxInt64) // read in Photon
	b.handleMessage(textEvent(alice, alice, "m2", "new", 200))
	b.handleMessage(revokeEvent(alice, alice, "r1", "m1", 201))

	if got := unreadCount(t, b, alice.String()); got != 1 {
		t.Errorf("unread = %d, want 1 (m2 is still unread; m1 had been read)", got)
	}
}

func TestHandleMessage_revokeRemovesDownloadedMedia(t *testing.T) {
	b := newClientBridge(t)
	b.dataDir = t.TempDir()
	alice := types.NewJID("447700900001", types.DefaultUserServer)
	os.MkdirAll(filepath.Join(b.dataDir, "media"), 0755)
	media := filepath.Join(b.dataDir, "media", "m1.jpg")
	os.WriteFile(media, []byte("jpeg"), 0644)

	b.handleMessage(messageEvent(alice, alice, "m1", 100, &waE2E.Message{
		ImageMessage: &waE2E.ImageMessage{Mimetype: proto.String("image/jpeg")},
	}))
	b.UpdateMediaURL("m1", media)
	b.handleMessage(revokeEvent(alice, alice, "r1", "m1", 101))

	if _, err := os.Stat(media); !os.IsNotExist(err) {
		t.Errorf("downloaded media for a revoked message still on disk")
	}
	var ct string
	b.msgDB.QueryRow(`SELECT content_type FROM messages WHERE id = 'm1'`).Scan(&ct)
	if ct != "text" {
		t.Errorf("content_type = %q, want text so it renders as [ deleted ]", ct)
	}
}

func editProtocol(targetID string, edited *waE2E.Message) *waE2E.Message {
	return &waE2E.Message{ProtocolMessage: &waE2E.ProtocolMessage{
		Type:          waE2E.ProtocolMessage_MESSAGE_EDIT.Enum(),
		Key:           &waCommon.MessageKey{ID: proto.String(targetID)},
		EditedMessage: edited,
	}}
}

func TestHandleMessage_editReplacesText(t *testing.T) {
	alice := types.NewJID("447700900001", types.DefaultUserServer)
	cases := []struct {
		name string
		evt  *events.Message
	}{
		{
			// What UnwrapRaw leaves after stripping an EditedMessage wrapper.
			"unwrapped EditedMessage",
			func() *events.Message {
				e := messageEvent(alice, alice, "e1", 101, editProtocol("m1", &waE2E.Message{Conversation: proto.String("fixed typo")}))
				e.IsEdit = true
				return e
			}(),
		},
		{
			"bare MESSAGE_EDIT protocol message",
			messageEvent(alice, alice, "e1", 101, editProtocol("m1", &waE2E.Message{
				ExtendedTextMessage: &waE2E.ExtendedTextMessage{Text: proto.String("fixed typo")},
			})),
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			b := newClientBridge(t)
			events := captureEvents(b)
			b.handleMessage(textEvent(alice, alice, "m1", "fixed tpyo", 100))
			b.handleMessage(tc.evt)

			if got := textBody(t, b, "m1"); got != "fixed typo" {
				t.Errorf("text = %q, want the edited text", got)
			}
			if v := rowCount(t, b, `SELECT edit_version FROM messages WHERE id = 'm1'`); v != 1 {
				t.Errorf("edit_version = %d, want 1", v)
			}
			if n := rowCount(t, b, `SELECT COUNT(*) FROM messages`); n != 1 {
				t.Errorf("messages = %d, want 1 (the edit is not a new message)", n)
			}
			if got := unreadCount(t, b, alice.String()); got != 1 {
				t.Errorf("unread = %d, want 1 (an edit is not a new unread message)", got)
			}
			if len(events.ofType("message_updated")) != 1 {
				t.Errorf("edit did not broadcast message_updated")
			}
		})
	}
}

func TestHandleMessage_unreadableEditKeepsText(t *testing.T) {
	b := newClientBridge(t)
	alice := types.NewJID("447700900001", types.DefaultUserServer)
	b.handleMessage(textEvent(alice, alice, "m1", "original", 100))
	b.handleMessage(messageEvent(alice, alice, "e1", 101, editProtocol("m1", nil)))

	if got := textBody(t, b, "m1"); got != "original" {
		t.Errorf("text = %q, an edit with no content must not blank the message", got)
	}
}

var (
	grp   = types.NewJID("120363000000000009", types.GroupServer)
	alice = types.NewJID("447700900001", types.DefaultUserServer)
	bob   = types.NewJID("447700900002", types.DefaultUserServer)
)

func editEvent(chat, sender types.JID, id, targetID, text string) *events.Message {
	return messageEvent(chat, sender, id, 200, editProtocol(targetID, &waE2E.Message{Conversation: proto.String(text)}))
}

// Message IDs are visible to every group member and the server can't check
// an end-to-end encrypted edit, so the bridge must.
func TestHandleMessage_editOnlyByTheSenderInTheSameChat(t *testing.T) {
	b := newClientBridge(t)
	b.handleMessage(textEvent(grp, alice, "m1", "meet at 6", 100))

	b.handleMessage(editEvent(grp, bob, "e1", "m1", "meet at 9")) // another member
	b.handleMessage(editEvent(bob, bob, "e2", "m1", "meet at 9")) // from another chat
	if got := textBody(t, b, "m1"); got != "meet at 6" {
		t.Fatalf("text = %q: someone other than the sender edited it", got)
	}

	b.handleMessage(editEvent(grp, alice, "e3", "m1", "meet at 7"))
	if got := textBody(t, b, "m1"); got != "meet at 7" {
		t.Errorf("text = %q, want the sender's edit", got)
	}
}

func TestHandleMessage_ownEditOnlyTouchesOwnMessages(t *testing.T) {
	b := newClientBridge(t)
	b.handleMessage(textEvent(alice, alice, "m1", "theirs", 100))
	b.handleMessage(editEvent(alice, ownJID, "e1", "m1", "rewritten"))
	if got := textBody(t, b, "m1"); got != "theirs" {
		t.Errorf("text = %q: our edit rewrote the other person's message", got)
	}
}

func TestHandleMessage_revokeOnlyBySenderOrAdmin(t *testing.T) {
	b := newClientBridge(t)
	b.handleMessage(textEvent(grp, alice, "m1", "one", 100))
	b.handleMessage(textEvent(grp, alice, "m2", "two", 101))
	b.handleMessage(textEvent(alice, alice, "d1", "dm", 102))

	b.handleMessage(revokeEvent(grp, bob, "r1", "m1", 110)) // not the sender, not an admin
	b.handleMessage(revokeEvent(bob, bob, "r2", "d1", 111)) // from another chat
	if messageStatus(t, b, "m1") == "deleted" || messageStatus(t, b, "d1") == "deleted" {
		t.Fatal("a revoke from someone not entitled to it was applied")
	}

	// A group admin may delete anyone's message.
	b.groupNames = map[string]cachedGroupName{
		grp.String(): {name: "G", admins: map[string]bool{bob.User: true}, fetched: time.Now()},
	}
	b.handleMessage(revokeEvent(grp, bob, "r3", "m1", 112))
	if got := messageStatus(t, b, "m1"); got != "deleted" {
		t.Errorf("admin revoke: status = %q", got)
	}

	// So may the sender, and the user's own devices (they only can if admin).
	b.handleMessage(revokeEvent(alice, alice, "r4", "d1", 113))
	b.handleMessage(revokeEvent(grp, ownJID, "r5", "m2", 114))
	if messageStatus(t, b, "d1") != "deleted" || messageStatus(t, b, "m2") != "deleted" {
		t.Errorf("sender/own revokes not applied: d1=%s m2=%s", messageStatus(t, b, "d1"), messageStatus(t, b, "m2"))
	}
}
