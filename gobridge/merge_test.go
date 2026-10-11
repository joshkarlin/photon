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

	b.UpsertConversation(pn.String(), named("Alice"), "p1", 100)
	b.UpsertConversation(lid.String(), rankedName{}, "l1", 200)
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

// Folding a LID row into its phone row keeps the stronger of their names.
func TestMergeLIDConversations_keepsTheStrongerName(t *testing.T) {
	ctx := context.Background()
	for _, tc := range []struct {
		name          string
		phone, lidRow rankedName
		want          string
	}{
		{"the LID row's address-book name wins", named("ally"), rankedName{name: "Alice", rank: rankContact}, "Alice"},
		{"the phone row's address-book name stays", rankedName{name: "Alice", rank: rankContact}, named("ally"), "Alice"},
		{"at equal rank the phone row's stays", named("ally"), named("al"), "ally"},
		{"a nameless phone row takes the LID row's", rankedName{}, named("al"), "al"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			b := newClientBridge(t)
			pn := types.NewJID("447700900001", types.DefaultUserServer)
			lid := types.NewJID("11223344", types.HiddenUserServer)
			b.client.Store.LIDs.PutLIDMapping(ctx, lid, pn)
			b.UpsertConversation(pn.String(), tc.phone, "p1", 100)
			b.UpsertConversation(lid.String(), tc.lidRow, "l1", 200)

			b.MergeLIDConversations()

			if got := conversationName(t, b, pn.String()); got != tc.want {
				t.Errorf("name = %q, want %q", got, tc.want)
			}
		})
	}
}

// Re-keying a group member from their LID to their number keeps the
// stronger of the two rows' names.
func TestCanonicalizeSenderJIDs_keepsTheStrongerParticipantName(t *testing.T) {
	b := newClientBridge(t)
	pn := types.NewJID("447700900003", types.DefaultUserServer)
	lid := types.NewJID("55667788", types.HiddenUserServer)
	b.client.Store.LIDs.PutLIDMapping(context.Background(), lid, pn)
	const grp = "120363000000000042@g.us"
	b.UpsertConversation(grp, rankedName{}, "m1", 100)
	b.InsertMessage(&MessageRow{ID: "m1", ConversationJID: grp, SenderJID: lid.String(), Timestamp: 100, ContentType: "text"})
	b.UpsertParticipant(grp, lid.String(), rankedName{name: "Carol Smith", rank: rankContact})
	b.UpsertParticipant(grp, pn.String(), named("caz"))

	b.CanonicalizeSenderJIDs()

	if got := b.storedParticipantName(grp, pn.String()); got != (rankedName{name: "Carol Smith", rank: rankContact}) {
		t.Errorf("phone row = %+v, want the LID row's address-book name", got)
	}
	if n := rowCount(t, b, `SELECT COUNT(*) FROM participants WHERE jid = ?`, lid.String()); n != 0 {
		t.Errorf("LID participant row survived")
	}
}
