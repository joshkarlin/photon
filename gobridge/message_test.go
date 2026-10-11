package main

import (
	"testing"

	"go.mau.fi/whatsmeow/types"
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
