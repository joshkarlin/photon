package main

import (
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
	b.UpsertConversation(jid, "Alice", false, "in1", 2)
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
	b.applyReceipt(jid, []string{"out1", "in1"}, types.ReceiptTypeRead, false)

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

			b.applyReceipt(jid, []string{"in1"}, tc.typ, true)

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
	b.UpsertConversation(jid, "Alice", false, "", 0)
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
