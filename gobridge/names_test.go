package main

import (
	"context"
	"testing"

	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/proto/waVnameCert"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

func conversationName(t *testing.T, b *Bridge, jid string) string {
	t.Helper()
	var name string
	if err := b.msgDB.QueryRow(`SELECT COALESCE(name, '') FROM conversations WHERE jid = ?`, jid).Scan(&name); err != nil {
		t.Fatalf("name for %s: %v", jid, err)
	}
	return name
}

func verifiedEvent(chat types.JID, id, pushName, verified string) *events.Message {
	evt := textEvent(chat, chat, id, "hello", 100)
	evt.Info.PushName = pushName
	evt.Info.VerifiedName = &types.VerifiedName{Details: &waVnameCert.VerifiedNameCertificate_Details{VerifiedName: s(verified)}}
	return evt
}

func TestHandleMessage_verifiedBusinessNameBeatsPushName(t *testing.T) {
	b := newClientBridge(t)
	shop := types.NewJID("447700900099", types.DefaultUserServer)

	b.handleMessage(textEvent(shop, shop, "m0", "earlier", 90)) // named by nothing yet
	b.handleMessage(verifiedEvent(shop, "m1", "acme help", "ACME Ltd"))

	if got := conversationName(t, b, shop.String()); got != "ACME Ltd" {
		t.Errorf("name = %q, want the verified business name", got)
	}
}

func TestHandleMessage_addressBookBeatsVerifiedName(t *testing.T) {
	b := newClientBridge(t)
	shop := types.NewJID("447700900099", types.DefaultUserServer)
	b.client.Store.Contacts.PutContactName(context.Background(), shop, "Corner", "Corner Shop")

	b.handleMessage(verifiedEvent(shop, "m1", "acme help", "ACME Ltd"))

	if got := conversationName(t, b, shop.String()); got != "Corner Shop" {
		t.Errorf("name = %q, want the address-book name", got)
	}
}

func TestHandleBusinessName(t *testing.T) {
	ctx := context.Background()
	shop := types.NewJID("447700900099", types.DefaultUserServer)
	shopLID := types.NewJID("99887766", types.HiddenUserServer)

	t.Run("renames a push-named DM, keyed via LID", func(t *testing.T) {
		b := newClientBridge(t)
		sent := captureEvents(b)
		b.client.Store.LIDs.PutLIDMapping(ctx, shopLID, shop)
		b.UpsertConversation(shop.String(), named("acme help"), "m1", 100)

		lidDevice := shopLID
		lidDevice.Device = 3
		b.handleBusinessName(&events.BusinessName{JID: lidDevice, NewBusinessName: "ACME Ltd"})

		if got := conversationName(t, b, shop.String()); got != "ACME Ltd" {
			t.Errorf("name = %q", got)
		}
		if len(sent.ofType("conversation_updated")) != 1 {
			t.Errorf("no conversation_updated for the rename")
		}
	})

	t.Run("address book wins", func(t *testing.T) {
		b := newClientBridge(t)
		b.client.Store.Contacts.PutContactName(ctx, shop, "Corner", "Corner Shop")
		b.UpsertConversation(shop.String(), rankedName{name: "Corner Shop", rank: rankContact}, "m1", 100)

		b.handleBusinessName(&events.BusinessName{JID: shop, NewBusinessName: "ACME Ltd"})

		if got := conversationName(t, b, shop.String()); got != "Corner Shop" {
			t.Errorf("name = %q, want the address-book name kept", got)
		}
	})
}

func TestBackfillContactNames_namesLIDChats(t *testing.T) {
	ctx := context.Background()
	b := newClientBridge(t)
	lid := types.NewJID("55443322", types.HiddenUserServer)
	b.client.Store.Contacts.PutPushName(ctx, lid, "Bob")
	b.UpsertConversation(lid.String(), rankedName{}, "m1", 100)

	b.BackfillContactNames()

	if got := conversationName(t, b, lid.String()); got != "Bob" {
		t.Errorf("@lid chat name = %q, want Bob from the contact store", got)
	}
}

func TestTyping_resolvesLIDs(t *testing.T) {
	b := newClientBridge(t)
	sent := captureEvents(b)
	pn := types.NewJID("447700900001", types.DefaultUserServer)
	lid := types.NewJID("11223344", types.HiddenUserServer)
	b.client.Store.LIDs.PutLIDMapping(context.Background(), lid, pn)
	lidDevice := lid
	lidDevice.Device = 2

	b.HandleEvent(&events.ChatPresence{
		MessageSource: types.MessageSource{Chat: lid, Sender: lidDevice},
		State:         types.ChatPresenceComposing,
	})

	got := sent.ofType("typing")
	if len(got) != 1 || got[0]["jid"] != pn.String() || got[0]["sender_jid"] != pn.String() || got[0]["composing"] != true {
		t.Errorf("typing = %v, want chat and sender as %s", got, pn)
	}
}

// Live messages, history sync and group open name a member the same way:
// the address book first, then the push name the message carried.
func TestMemberName_sameOnLiveHistoryAndGroupOpen(t *testing.T) {
	ctx := context.Background()
	group := types.NewJID("120363000000000042", types.GroupServer)
	carol := types.NewJID("447700900003", types.DefaultUserServer) // in the address book
	dave := types.NewJID("447700900004", types.DefaultUserServer)  // only a push name

	live := newClientBridge(t)
	live.client.Store.Contacts.PutContactName(ctx, carol, "Carol", "Carol Smith")
	for _, m := range []struct {
		sender types.JID
		id     string
		push   string
	}{{carol, "l1", "caz"}, {dave, "l2", "Dave G"}} {
		evt := textEvent(group, m.sender, m.id, "hi", 100)
		evt.Info.PushName = m.push
		live.handleMessage(evt)
	}

	history := newClientBridge(t)
	history.retention = RetentionConfig{MaxMessages: 50}
	history.client.Store.Contacts.PutContactName(ctx, carol, "Carol", "Carol Smith")
	withPush := func(m *waHistorySync.HistorySyncMsg, push string) *waHistorySync.HistorySyncMsg {
		m.Message.PushName = s(push)
		return m
	}
	history.handleHistorySync(&events.HistorySync{Data: &waHistorySync.HistorySync{
		Conversations: []*waHistorySync.Conversation{{
			ID: s(group.String()),
			Messages: []*waHistorySync.HistorySyncMsg{
				withPush(historyMsg(group, "h1", false, carol.String(), 1_900_000_000, &waE2E.Message{Conversation: s("hi")}), "caz"),
				withPush(historyMsg(group, "h2", false, dave.String(), 1_900_000_001, &waE2E.Message{Conversation: s("hi")}), "Dave G"),
			},
		}},
	}})

	want := map[string]string{carol.String(): "Carol Smith", dave.String(): "Dave G"}
	for name, b := range map[string]*Bridge{"live": live, "history": history} {
		for _, step := range []string{"stored", "after group open"} {
			got := participantNames(t, b, group.String())
			for jid, n := range want {
				if got[jid] != n {
					t.Errorf("%s, %s: %s = %q, want %q", name, step, jid, got[jid], n)
				}
			}
			b.ResolveGroupParticipants(group.String())
		}
	}
}

// An @lid DM whose number is known is stored under the number but never
// named by it: with nothing better the name stays empty for Kotlin to fill.
func TestHandleMessage_LIDChatIsNeverNamedByItsNumber(t *testing.T) {
	b := newClientBridge(t)
	pn := types.NewJID("447700900001", types.DefaultUserServer)
	lid := types.NewJID("11223344", types.HiddenUserServer)
	b.client.Store.LIDs.PutLIDMapping(context.Background(), lid, pn)

	b.handleMessage(textEvent(lid, lid, "m1", "hi", 100))
	if got := conversationRank(t, b, pn.String()); got != (rankedName{}) {
		t.Errorf("name = %+v, want none", got)
	}

	evt := textEvent(lid, lid, "m2", "hi again", 101)
	evt.Info.PushName = "+44 7700 900001" // a push name that is only a number
	b.handleMessage(evt)
	if got := conversationRank(t, b, pn.String()); got != (rankedName{}) {
		t.Errorf("name = %+v after a numeric push name, want none", got)
	}
}

// A business chat keeps its verified name when the person's push name
// changes, whether the change comes with a message or as its own event.
func TestBusinessChat_keepsVerifiedNameOverPushNames(t *testing.T) {
	b := newClientBridge(t)
	shop := types.NewJID("447700900099", types.DefaultUserServer)
	b.handleMessage(verifiedEvent(shop, "m1", "acme help", "ACME Ltd"))

	b.handlePushName(&events.PushName{JID: shop, OldPushName: "acme help", NewPushName: "acme support"})
	later := textEvent(shop, shop, "m2", "still here", 200)
	later.Info.PushName = "acme support"
	b.handleMessage(later)

	if got := conversationRank(t, b, shop.String()); got != (rankedName{name: "ACME Ltd", rank: rankBusiness}) {
		t.Errorf("name = %+v, want the verified name kept", got)
	}
}

// History sync can land after live messages named a chat; its older names
// fill gaps but never replace a live one.
func TestHistorySync_neverRenamesOverLiveNames(t *testing.T) {
	b := newClientBridge(t)
	b.retention = RetentionConfig{MaxMessages: 50}
	alice := types.NewJID("447700900001", types.DefaultUserServer)
	dave := types.NewJID("447700900004", types.DefaultUserServer)
	grp := types.NewJID("120363000000000042", types.GroupServer)

	live := textEvent(alice, alice, "l1", "hi", 1_900_000_100)
	live.Info.PushName = "Ally"
	b.handleMessage(live)
	inGroup := textEvent(grp, dave, "l2", "hi all", 1_900_000_100)
	inGroup.Info.PushName = "Dave G"
	b.handleMessage(inGroup)
	b.handleGroupInfo(&events.GroupInfo{JID: grp, Name: &types.GroupName{Name: "Book club"}})

	withPush := func(m *waHistorySync.HistorySyncMsg, push string) *waHistorySync.HistorySyncMsg {
		m.Message.PushName = s(push)
		return m
	}
	b.handleHistorySync(&events.HistorySync{Data: &waHistorySync.HistorySync{
		Conversations: []*waHistorySync.Conversation{
			{
				ID:       s(alice.String()),
				Name:     s("Alice (old)"),
				Messages: []*waHistorySync.HistorySyncMsg{withPush(historyMsg(alice, "h1", false, "", 1_900_000_000, &waE2E.Message{Conversation: s("old")}), "Alison")},
			},
			{
				ID:       s(grp.String()),
				Name:     s("Reading group"),
				Messages: []*waHistorySync.HistorySyncMsg{withPush(historyMsg(grp, "h2", false, dave.String(), 1_900_000_000, &waE2E.Message{Conversation: s("old")}), "Dave")},
			},
		},
	}})

	if got := conversationName(t, b, alice.String()); got != "Ally" {
		t.Errorf("DM = %q, want the live push name", got)
	}
	if got := conversationName(t, b, grp.String()); got != "Book club" {
		t.Errorf("group = %q, want the live subject", got)
	}
	if got := participantNames(t, b, grp.String())[dave.String()]; got != "Dave G" {
		t.Errorf("member = %q, want the live push name", got)
	}
}

// History names a chat nobody named yet: a DM by its chat name, a group by
// its subject.
func TestHistorySync_namesNewChats(t *testing.T) {
	b := newClientBridge(t)
	b.retention = RetentionConfig{MaxMessages: 50}
	alice := types.NewJID("447700900001", types.DefaultUserServer)
	bob := types.NewJID("447700900002", types.DefaultUserServer)
	grp := types.NewJID("120363000000000042", types.GroupServer)
	msg := func(chat types.JID, id, push string) *waHistorySync.HistorySyncMsg {
		m := historyMsg(chat, id, false, "", 1_900_000_000, &waE2E.Message{Conversation: s("hi")})
		m.Message.PushName = s(push)
		return m
	}
	b.handleHistorySync(&events.HistorySync{Data: &waHistorySync.HistorySync{
		Conversations: []*waHistorySync.Conversation{
			{ID: s(alice.String()), Name: s("Alice"), Messages: []*waHistorySync.HistorySyncMsg{msg(alice, "h1", "ally")}},
			{ID: s(bob.String()), Name: s("+44 7700 900002"), Messages: []*waHistorySync.HistorySyncMsg{msg(bob, "h2", "Bobby")}},
			{ID: s(grp.String()), Name: s("Book club")},
		},
	}})

	for jid, want := range map[string]rankedName{
		alice.String(): {name: "Alice", rank: rankPush},
		bob.String():   {name: "Bobby", rank: rankPush},
		grp.String():   {name: "Book club", rank: rankSubject},
	} {
		if got := conversationRank(t, b, jid); got != want {
			t.Errorf("%s = %+v, want %+v", jid, got, want)
		}
	}
}
