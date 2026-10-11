package main

import (
	"context"
	"fmt"
	"testing"

	"go.mau.fi/whatsmeow/types"
)

func TestReceiptEffect(t *testing.T) {
	cases := []struct {
		name       string
		typ        types.ReceiptType
		isFromMe   bool
		wantOwn    bool
		wantStatus string
	}{
		{"read-self is our own read", types.ReceiptTypeReadSelf, true, true, ""},
		{"read from our own device is our own read", types.ReceiptTypeRead, true, true, ""},
		{"contact read only advances our outgoing status", types.ReceiptTypeRead, false, false, "read"},
		{"contact delivered", types.ReceiptTypeDelivered, false, false, "delivered"},
		{"delivered to our own device is ignored", types.ReceiptTypeDelivered, true, false, ""},
		{"sender receipt ignored", types.ReceiptTypeSender, true, false, ""},
		{"played ignored", types.ReceiptTypePlayed, false, false, ""},
		{"retry ignored", types.ReceiptTypeRetry, false, false, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			own, status := receiptEffect(tc.typ, tc.isFromMe)
			if own != tc.wantOwn || status != tc.wantStatus {
				t.Errorf("receiptEffect(%q, %v) = (%v, %q), want (%v, %q)", tc.typ, tc.isFromMe, own, status, tc.wantOwn, tc.wantStatus)
			}
		})
	}
}

// seedChat creates a DM with one outgoing and one incoming message and an
// unread badge of 1.
func seedChat(t *testing.T, b *Bridge, jid string) {
	t.Helper()
	b.UpsertConversation(jid, named("Alice"), "in1", 2)
	if err := b.InsertMessages([]*MessageRow{
		{ID: "out1", ConversationJID: jid, SenderJID: "me@s.whatsapp.net", Timestamp: 1, ContentType: "text", IsFromMe: true, Status: "sent"},
		{ID: "in1", ConversationJID: jid, SenderJID: jid, Timestamp: 2, ContentType: "text", Status: "sent"},
	}); err != nil {
		t.Fatalf("insert: %v", err)
	}
	b.IncrementUnread(jid)
}

func TestApplyReceipt_contactReadKeepsUnread(t *testing.T) {
	b := newTestBridge(t)
	const jid = "447@s.whatsapp.net"
	seedChat(t, b, jid)

	// The contact read our outgoing message — our own unread must survive.
	b.applyReceipt(jid, []string{"out1", "in1"}, types.ReceiptTypeRead, false, 3)

	if got := unreadCount(t, b, jid); got != 1 {
		t.Errorf("unread = %d, want 1 (a contact's read receipt must not clear our badge)", got)
	}
	if got := messageStatus(t, b, "out1"); got != "read" {
		t.Errorf("outgoing status = %q, want read", got)
	}
	if got := messageStatus(t, b, "in1"); got != "sent" {
		t.Errorf("incoming status = %q, want unchanged (sent)", got)
	}
}

func TestApplyReceipt_ownReadClearsUnread(t *testing.T) {
	for _, tc := range []struct {
		name string
		typ  types.ReceiptType
	}{
		{"read-self", types.ReceiptTypeReadSelf},
		{"read from own device", types.ReceiptTypeRead},
	} {
		t.Run(tc.name, func(t *testing.T) {
			b := newTestBridge(t)
			const jid = "447@s.whatsapp.net"
			seedChat(t, b, jid)

			b.applyReceipt(jid, []string{"in1"}, tc.typ, true, 3)

			if got := unreadCount(t, b, jid); got != 0 {
				t.Errorf("unread = %d, want 0", got)
			}
			if got := messageStatus(t, b, "in1"); got != "read" {
				t.Errorf("incoming status = %q, want read", got)
			}
			if got := messageStatus(t, b, "out1"); got != "sent" {
				t.Errorf("outgoing status = %q, want unchanged (sent)", got)
			}
		})
	}
}

func TestAdvanceOutgoingStatus_neverDowngradesOrResurrects(t *testing.T) {
	b := newTestBridge(t)
	const jid = "447@s.whatsapp.net"
	b.UpsertConversation(jid, named("Alice"), "", 0)
	b.InsertMessages([]*MessageRow{
		{ID: "r", ConversationJID: jid, SenderJID: "me", Timestamp: 1, ContentType: "text", IsFromMe: true, Status: "read"},
		{ID: "d", ConversationJID: jid, SenderJID: "me", Timestamp: 2, ContentType: "text", IsFromMe: true, Status: "deleted"},
		{ID: "f", ConversationJID: jid, SenderJID: "me", Timestamp: 3, ContentType: "text", IsFromMe: true, Status: "failed"},
	})

	b.AdvanceOutgoingStatus([]string{"r", "d", "f"}, "delivered")

	if got := messageStatus(t, b, "r"); got != "read" {
		t.Errorf("late delivered downgraded read to %q", got)
	}
	if got := messageStatus(t, b, "d"); got != "deleted" {
		t.Errorf("receipt resurrected a deleted row as %q", got)
	}
	if got := messageStatus(t, b, "f"); got != "delivered" {
		t.Errorf("failed row = %q, want delivered (the receipt proves it went through)", got)
	}
}

func unreadCount(t *testing.T, b *Bridge, jid string) int {
	t.Helper()
	var n int
	if err := b.msgDB.QueryRow(`SELECT unread_count FROM conversations WHERE jid = ?`, jid).Scan(&n); err != nil {
		t.Fatalf("unread for %s: %v", jid, err)
	}
	return n
}

func messageStatus(t *testing.T, b *Bridge, id string) string {
	t.Helper()
	var s string
	if err := b.msgDB.QueryRow(`SELECT status FROM messages WHERE id = ?`, id).Scan(&s); err != nil {
		t.Fatalf("status for %s: %v", id, err)
	}
	return s
}

func TestApplyChatReadState(t *testing.T) {
	b := newTestBridge(t)
	events := captureEvents(b)
	const jid = "447@s.whatsapp.net"
	b.UpsertConversation(jid, named("Alice"), "m2", 20)
	b.InsertMessages([]*MessageRow{
		{ID: "m1", ConversationJID: jid, SenderJID: jid, Timestamp: 10, ContentType: "text", Status: "sent"},
		{ID: "m2", ConversationJID: jid, SenderJID: jid, Timestamp: 20, ContentType: "text", Status: "sent"},
	})
	b.IncrementUnread(jid)
	b.IncrementUnread(jid)

	// Read on the primary when only m1 had arrived: m2 stays unread.
	b.applyChatReadState(jid, true, 15)
	if got := unreadCount(t, b, jid); got != 1 {
		t.Errorf("unread after partial read = %d, want 1", got)
	}
	if messageStatus(t, b, "m1") != "read" || messageStatus(t, b, "m2") != "sent" {
		t.Errorf("statuses = %s/%s, want read/sent", messageStatus(t, b, "m1"), messageStatus(t, b, "m2"))
	}
	if n := len(events.ofType("read_self")); n != 0 {
		t.Errorf("read_self broadcast while still unread (%d)", n)
	}

	// Read everything: unread clears and Kotlin is told to drop the banner.
	b.applyChatReadState(jid, true, 20)
	if got := unreadCount(t, b, jid); got != 0 {
		t.Errorf("unread after full read = %d, want 0", got)
	}
	if got := events.ofType("read_self"); len(got) != 1 || got[0]["conversation_jid"] != jid {
		t.Errorf("read_self events = %v, want one for %s", got, jid)
	}

	// Marked unread on the primary: the chat shows unread again.
	b.applyChatReadState(jid, false, 0)
	if got := unreadCount(t, b, jid); got != 1 {
		t.Errorf("unread after mark-unread = %d, want 1", got)
	}
	// Marking unread never lowers a real count.
	b.IncrementUnread(jid)
	b.applyChatReadState(jid, false, 0)
	if got := unreadCount(t, b, jid); got != 2 {
		t.Errorf("unread after second mark-unread = %d, want 2", got)
	}
}

// threeUnread seeds a DM with three unread incoming messages: m1 and m2 in
// the same second, then m3.
func threeUnread(t *testing.T, b *Bridge, jid string) {
	t.Helper()
	b.UpsertConversation(jid, named("Alice"), "m3", 20)
	if err := b.InsertMessages([]*MessageRow{
		{ID: "m1", ConversationJID: jid, SenderJID: jid, Timestamp: 10, ContentType: "text", Status: "sent"},
		{ID: "m2", ConversationJID: jid, SenderJID: jid, Timestamp: 10, ContentType: "text", Status: "sent"},
		{ID: "m3", ConversationJID: jid, SenderJID: jid, Timestamp: 20, ContentType: "text", Status: "sent"},
	}); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 3; i++ {
		b.IncrementUnread(jid)
	}
}

// Reading on the primary marks only what was read: messages that arrived
// after it (or later in the same second) keep the chat unread.
func TestApplyReceipt_ownReadIsBoundedByTheMessagesRead(t *testing.T) {
	b := newTestBridge(t)
	sent := captureEvents(b)
	const jid = "447@s.whatsapp.net"
	threeUnread(t, b, jid)

	b.applyReceipt(jid, []string{"m1"}, types.ReceiptTypeReadSelf, true, 10)
	if got := unreadCount(t, b, jid); got != 2 {
		t.Errorf("unread = %d, want 2", got)
	}
	if messageStatus(t, b, "m1") != "read" || messageStatus(t, b, "m2") != "sent" || messageStatus(t, b, "m3") != "sent" {
		t.Errorf("statuses = %s/%s/%s, want read/sent/sent", messageStatus(t, b, "m1"), messageStatus(t, b, "m2"), messageStatus(t, b, "m3"))
	}
	if n := len(sent.ofType("read_self")); n != 0 {
		t.Errorf("read_self sent while the chat is still unread")
	}
	if n := len(sent.ofType("conversation_updated")); n != 1 {
		t.Errorf("conversation_updated = %d, want 1", n)
	}

	b.applyReceipt(jid, []string{"m3", "m2"}, types.ReceiptTypeReadSelf, true, 30)
	if got := unreadCount(t, b, jid); got != 0 {
		t.Errorf("unread = %d, want 0", got)
	}
	if got := sent.ofType("read_self"); len(got) != 1 || got[0]["conversation_jid"] != jid {
		t.Errorf("read_self = %v", got)
	}
}

func TestApplyReceipt_ownReadOfUnstoredMessagesUsesItsTime(t *testing.T) {
	b := newTestBridge(t)
	const jid = "447@s.whatsapp.net"
	threeUnread(t, b, jid)

	// A receipt for messages Photon never stored, sent at 15: m3 (20) is newer.
	b.applyReceipt(jid, []string{"dropped"}, types.ReceiptTypeReadSelf, true, 15)
	if got := unreadCount(t, b, jid); got != 1 {
		t.Errorf("unread = %d, want 1 (m3 arrived after the read)", got)
	}
}

// mark_read from the open chat reads through the newest row it listed, not
// messages that arrived after the list was loaded.
func TestHandleMarkRead_boundedByTheListedRows(t *testing.T) {
	b := newClientBridge(t)
	const jid = "447700900001@s.whatsapp.net"
	threeUnread(t, b, jid)

	// The UI had m2 as its newest; the receipt itself fails offline.
	b.handleMarkRead(context.Background(), mustMarshal(MarkReadPayload{JID: jid, MessageIDs: []string{"m2", "m1"}}))

	if got := unreadCount(t, b, jid); got != 1 {
		t.Errorf("unread = %d, want 1 (m3 was not on screen)", got)
	}
	if messageStatus(t, b, "m3") != "sent" {
		t.Errorf("m3 status = %s, want still unread", messageStatus(t, b, "m3"))
	}
}

// Rows from builds before per-row read state are all "sent"; the migration
// keeps only the newest unread_count of them unread, so a revoke of an old
// message can't clear the badge of the new one.
func TestMigrateIncomingReadState(t *testing.T) {
	b := newTestBridge(t)
	const jid = "447700900001@s.whatsapp.net"
	b.UpsertConversation(jid, named("Alice"), "new", 40)
	b.InsertMessages([]*MessageRow{
		{ID: "old1", ConversationJID: jid, SenderJID: jid, Timestamp: 10, ContentType: "text", Status: "sent"},
		{ID: "old2", ConversationJID: jid, SenderJID: jid, Timestamp: 20, ContentType: "text", Status: "sent"},
		{ID: "gone", ConversationJID: jid, SenderJID: jid, Timestamp: 25, ContentType: "text", Status: "deleted"},
		{ID: "mine", ConversationJID: jid, SenderJID: "me", Timestamp: 30, ContentType: "text", IsFromMe: true, Status: "sent"},
		{ID: "new", ConversationJID: jid, SenderJID: jid, Timestamp: 40, ContentType: "text", Status: "sent"},
		// A chat with no unread count at all.
		{ID: "other", ConversationJID: "x@s.whatsapp.net", SenderJID: "x@s.whatsapp.net", Timestamp: 5, ContentType: "text", Status: "sent"},
	})
	b.UpsertConversation("x@s.whatsapp.net", named("X"), "other", 5)
	b.IncrementUnread(jid)
	rerunMigrationsFrom(t, b, 1)

	want := map[string]string{"old1": "read", "old2": "read", "gone": "deleted", "mine": "sent", "new": "sent", "other": "read"}
	for id, status := range want {
		if got := messageStatus(t, b, id); got != status {
			t.Errorf("%s status = %q, want %q", id, got, status)
		}
	}

	b.dataDir = t.TempDir()
	alice := types.NewJID("447700900001", types.DefaultUserServer)
	b.handleMessage(revokeEvent(alice, alice, "r1", "old1", 50))
	if got := unreadCount(t, b, jid); got != 1 {
		t.Errorf("unread after revoking an old message = %d, want 1", got)
	}
}

// rerunMigrationsFrom winds the schema version back so migrations after
// `done` run again on InitMessageDB, as on an install upgrading from there.
func rerunMigrationsFrom(t *testing.T, b *Bridge, done int) {
	t.Helper()
	if _, err := b.msgDB.Exec(fmt.Sprintf(`PRAGMA user_version = %d`, done)); err != nil {
		t.Fatal(err)
	}
	if err := b.InitMessageDB(context.Background()); err != nil {
		t.Fatal(err)
	}
}
