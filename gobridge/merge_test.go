package main

import (
	"context"
	"sync"
	"testing"

	"go.mau.fi/whatsmeow/types"
)

func TestMergeLIDConversations_concurrentRunsCountOnce(t *testing.T) {
	b := newClientBridge(t)
	pn := types.NewJID("447700900001", types.DefaultUserServer)
	lid := types.NewJID("11223344", types.HiddenUserServer)
	b.client.Store.LIDs.PutLIDMapping(context.Background(), lid, pn)

	b.UpsertConversation(pn.String(), "Alice", "p1", 100)
	b.UpsertConversation(lid.String(), "", "l1", 200)
	b.InsertMessages([]*MessageRow{
		{ID: "p1", ConversationJID: pn.String(), SenderJID: pn.String(), Timestamp: 100, ContentType: "text"},
		{ID: "l1", ConversationJID: lid.String(), SenderJID: lid.String(), Timestamp: 200, ContentType: "text"},
	})
	b.IncrementUnread(pn.String())
	b.IncrementUnread(lid.String())
	b.IncrementUnread(lid.String())
	b.UpdateMute(lid.String(), 1_900_000_000) // muted while still keyed by LID

	// App-state patches arrive in bursts, each triggering a merge.
	var wg sync.WaitGroup
	for i := 0; i < 6; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			b.MergeLIDConversations()
		}()
	}
	wg.Wait()

	if got := unreadCount(t, b, pn.String()); got != 3 {
		t.Errorf("unread = %d, want 3 (1 + the LID row's 2, added once)", got)
	}
	if n := rowCount(t, b, `SELECT COUNT(*) FROM conversations WHERE jid = ?`, lid.String()); n != 0 {
		t.Errorf("LID row survived the merge")
	}
	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages WHERE conversation_jid = ?`, pn.String()); n != 2 {
		t.Errorf("messages under the phone JID = %d, want 2", n)
	}
	if got := mutedUntilColumn(t, b, pn.String()); got != 1_900_000_000 {
		t.Errorf("muted_until = %d, want the LID row's mute carried over", got)
	}
	var last string
	b.msgDB.QueryRow(`SELECT last_message_id FROM conversations WHERE jid = ?`, pn.String()).Scan(&last)
	if last != "l1" {
		t.Errorf("last_message_id = %q, want the newer LID message", last)
	}
}
