package main

import (
	"context"
	"database/sql"
	"os"
	"path/filepath"
	"testing"

	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
)

// A message row needs its conversation row (foreign key). These run on the
// message database as the bridge opens it, with foreign keys on; the other
// tests' in-memory database has them off, which hid that the first message
// of a chat was dropped.

// newFKClientBridge is newClientBridge on the message database as the bridge
// opens it: a file with foreign keys on.
func newFKClientBridge(t *testing.T) *Bridge {
	t.Helper()
	registerFKDriver()
	db, err := sql.Open("sqlite_fk", messageDBDSN(t.TempDir()))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	b := &Bridge{msgDB: db, log: waLog.Noop, groupNames: make(map[string]cachedGroupName)}
	if err := b.InitMessageDB(context.Background()); err != nil {
		t.Fatal(err)
	}
	b.client = newTestClient(t)
	captureEvents(b)
	return b
}

// storedIn reports the conversation a stored message is in, failing the test
// if it wasn't stored.
func storedIn(t *testing.T, b *Bridge, id string) (conversation, status string) {
	t.Helper()
	if err := b.msgDB.QueryRow(`SELECT conversation_jid, status FROM messages WHERE id = ?`, id).Scan(&conversation, &status); err != nil {
		t.Fatalf("message %s wasn't stored: %v", id, err)
	}
	return
}

func lastMessageOf(t *testing.T, b *Bridge, jid string) string {
	t.Helper()
	var last string
	if err := b.msgDB.QueryRow(`SELECT COALESCE(last_message_id, '') FROM conversations WHERE jid = ?`, jid).Scan(&last); err != nil {
		t.Fatalf("conversation %s: %v", jid, err)
	}
	return last
}

// The first message to someone Photon has never talked to (a chat started
// from New message). The offline client's send fails, so it's kept failed
// for a retry.
func TestFirstMessage_sentToAFreshJID(t *testing.T) {
	b := newFKClientBridge(t)
	jid := "447700900123@s.whatsapp.net"

	id, _, err := b.SendTextMessage(context.Background(), jid, "Hello", "")
	if err == nil {
		t.Fatal("the offline client sent")
	}

	if conv, status := storedIn(t, b, id); conv != jid || status != "failed" {
		t.Errorf("stored in %s as %s, want %s as failed", conv, status, jid)
	}
	if got := textBody(t, b, id); got != "Hello" {
		t.Errorf("text = %q", got)
	}
	if last := lastMessageOf(t, b, jid); last != id {
		t.Errorf("conversation's last message = %q, want %q", last, id)
	}
}

func TestFirstMessage_mediaSentToAFreshJID(t *testing.T) {
	b := newFKClientBridge(t)
	b.dataDir = t.TempDir()
	jid := "447700900124@s.whatsapp.net"
	path := filepath.Join(b.dataDir, "send_media_photo.jpg")
	if err := os.WriteFile(path, []byte("jpeg"), 0o600); err != nil {
		t.Fatal(err)
	}

	id, _, err := b.UploadAndSend(SendMediaPayload{JID: jid, FilePath: path, MimeType: "image/jpeg"})
	if err == nil {
		t.Fatal("the offline client uploaded")
	}

	if conv, status := storedIn(t, b, id); conv != jid || status != "failed" {
		t.Errorf("stored in %s as %s, want %s as failed", conv, status, jid)
	}
}

// Someone new writes first: their message opens the chat.
func TestFirstMessage_receivedFromSomeoneNew(t *testing.T) {
	b := newFKClientBridge(t)
	stranger := types.NewJID("447700900555", types.DefaultUserServer)

	b.handleMessage(textEvent(stranger, stranger, "IN1", "Hi, it's Sam", 1_900_000_000))

	if conv, _ := storedIn(t, b, "IN1"); conv != stranger.String() {
		t.Errorf("stored in %s, want %s", conv, stranger)
	}
	if last := lastMessageOf(t, b, stranger.String()); last != "IN1" {
		t.Errorf("conversation's last message = %q, want IN1", last)
	}
	var unread int
	b.msgDB.QueryRow(`SELECT unread_count FROM conversations WHERE jid = ?`, stranger.String()).Scan(&unread)
	if unread != 1 {
		t.Errorf("unread = %d, want 1", unread)
	}
}

func TestFirstMessage_aMissedCallFromANewNumber(t *testing.T) {
	b := newFKClientBridge(t)
	b.HandleEvent(&events.CallOffer{BasicCallMeta: callMeta("c1", caller, caller), Data: voiceOffer()})
	b.HandleEvent(&events.CallTerminate{BasicCallMeta: callMeta("c1", caller, caller)})

	if chat, _, _ := callRow(t, b, "c1"); chat != caller.String() {
		t.Errorf("call row in %s, want %s", chat, caller)
	}
	if last := lastMessageOf(t, b, caller.String()); last != callRowID("c1") {
		t.Errorf("conversation's last message = %q, want the call", last)
	}
}

// History for a chat the bridge hasn't seen live (a re-pair).
func TestFirstMessage_historyForANewChat(t *testing.T) {
	b := newFKClientBridge(t)
	b.retention = RetentionConfig{MaxMessages: 50}
	alice := types.NewJID("447700900001", types.DefaultUserServer)
	now := uint64(1_900_000_000)

	b.handleHistorySync(&events.HistorySync{Data: &waHistorySync.HistorySync{
		Conversations: []*waHistorySync.Conversation{{
			ID: s(alice.String()),
			Messages: []*waHistorySync.HistorySyncMsg{
				historyMsg(alice, "h1", false, "", now, &waE2E.Message{Conversation: s("First")}),
				historyMsg(alice, "h2", true, "", now+1, &waE2E.Message{Conversation: s("Reply")}),
			},
		}},
	}})

	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages WHERE conversation_jid = ?`, alice.String()); n != 2 {
		t.Errorf("stored %d history messages, want 2", n)
	}
}

// Storing a message into a chat that exists leaves the chat as it was: its
// name, and its last message, which callers move with UpsertConversation.
func TestFirstMessage_anExistingChatIsUntouched(t *testing.T) {
	b := newFKClientBridge(t)
	jid := "447700900777@s.whatsapp.net"
	b.UpsertConversation(jid, named("Alice"), "old", 1_800_000_000)

	if _, err := b.InsertMessage(&MessageRow{ID: "m1", ConversationJID: jid, SenderJID: jid, Timestamp: 1_900_000_000, ContentType: "text"}); err != nil {
		t.Fatal(err)
	}

	if got := conversationRank(t, b, jid).name; got != "Alice" {
		t.Errorf("name = %q, want Alice", got)
	}
	if last := lastMessageOf(t, b, jid); last != "old" {
		t.Errorf("last message = %q, want old", last)
	}
}
