package main

import (
	"math"
	"os"
	"path/filepath"
	"testing"

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
