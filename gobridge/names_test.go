package main

import (
	"context"
	"testing"
	"time"

	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/proto/waSyncAction"
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
		if got := sent.ofType("names_changed"); len(got) != 1 || !hasJID(got[0], shop.String()) {
			t.Errorf("names_changed = %v, want one naming the chat", got)
		}
	})

	t.Run("address book wins", func(t *testing.T) {
		b := newClientBridge(t)
		b.client.Store.Contacts.PutContactName(ctx, shop, "Corner", "Corner Shop")
		b.UpsertConversation(shop.String(), rankedName{name: "Corner Shop", rank: rankContact}, "m1", 100)

		sent := captureEvents(b)
		b.handleBusinessName(&events.BusinessName{JID: shop, NewBusinessName: "ACME Ltd"})

		if got := conversationName(t, b, shop.String()); got != "Corner Shop" {
			t.Errorf("name = %q, want the address-book name kept", got)
		}
		if got := sent.ofType("names_changed"); len(got) != 0 {
			t.Errorf("names_changed = %v for a name that didn't change", got)
		}
	})
}

func TestBackfillContactNames_namesLIDChats(t *testing.T) {
	ctx := context.Background()
	b := newClientBridge(t)
	lid := types.NewJID("55443322", types.HiddenUserServer)
	b.client.Store.Contacts.PutPushName(ctx, lid, "Bob")
	b.UpsertConversation(lid.String(), rankedName{}, "m1", 100)

	b.reconcile()

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

// whatsmeow keys an address-book entry by its app-state index, which may be
// the LID: a chat keyed by the number still finds it, and the other way
// round, even when the other form holds an entry of its own.
func TestLookupContact_readsPhoneAndLIDForms(t *testing.T) {
	ctx := context.Background()
	pn := types.NewJID("447700900001", types.DefaultUserServer)
	lid := types.NewJID("11223344", types.HiddenUserServer)

	t.Run("address book under the LID, push name under the number", func(t *testing.T) {
		b := newClientBridge(t)
		b.client.Store.LIDs.PutLIDMapping(ctx, lid, pn)
		b.client.Store.Contacts.PutPushName(ctx, pn, "ally")
		b.client.Store.Contacts.PutContactName(ctx, lid, "Alice", "Alice Smith")

		for _, form := range []types.JID{pn, lid} {
			if got := personName(b.lookupContact(form), "", ""); got != (rankedName{name: "Alice Smith", rank: rankContact}) {
				t.Errorf("from %s: %+v, want the address-book name", form, got)
			}
		}

		live := textEvent(pn, pn, "m1", "hi", 100)
		live.Info.PushName = "ally"
		b.handleMessage(live)
		if got := conversationRank(t, b, pn.String()); got != (rankedName{name: "Alice Smith", rank: rankContact}) {
			t.Errorf("chat = %+v, want the address-book name", got)
		}
	})

	t.Run("address book under the number for a LID-keyed lookup", func(t *testing.T) {
		b := newClientBridge(t)
		b.client.Store.LIDs.PutLIDMapping(ctx, lid, pn)
		b.client.Store.Contacts.PutContactName(ctx, pn, "Alice", "Alice Smith")
		if got := personName(b.lookupContact(lid), "", ""); got.name != "Alice Smith" {
			t.Errorf("from the LID: %+v", got)
		}
	})

	t.Run("the reconcile upgrades a chat named before the entry was found", func(t *testing.T) {
		b := newClientBridge(t)
		b.client.Store.LIDs.PutLIDMapping(ctx, lid, pn)
		b.UpsertConversation(pn.String(), named("ally"), "m1", 100)
		b.client.Store.Contacts.PutContactName(ctx, lid, "Alice", "Alice Smith")

		b.BackfillContactNames()

		if got := conversationRank(t, b, pn.String()); got != (rankedName{name: "Alice Smith", rank: rankContact}) {
			t.Errorf("chat = %+v, want the address-book name", got)
		}
	})
}

// hasJID reports whether a names_changed payload lists jid.
func hasJID(payload map[string]any, jid string) bool {
	jids, _ := payload["jids"].([]any)
	for _, j := range jids {
		if j == jid {
			return true
		}
	}
	return false
}

// An address-book edit on the phone reaches Photon as one events.Contact
// (whatsmeow stores it first). The person's DM and their name in every group
// they've written in follow it at once, and Kotlin hears which chats changed.
func TestHandleContact_renamesDMAndGroupMembers(t *testing.T) {
	ctx := context.Background()
	alice := types.NewJID("447700900001", types.DefaultUserServer)
	aliceLID := types.NewJID("11223344", types.HiddenUserServer)
	club := types.NewJID("120363000000000001", types.GroupServer)
	work := types.NewJID("120363000000000002", types.GroupServer)

	setup := func(t *testing.T) (*Bridge, *eventLog) {
		b := newClientBridge(t)
		b.client.Store.LIDs.PutLIDMapping(ctx, aliceLID, alice)
		b.client.Store.Contacts.PutContactName(ctx, aliceLID, "Alice", "Alice")
		b.handleMessage(textEvent(alice, alice, "d1", "hi", 100))
		b.handleMessage(textEvent(club, alice, "c1", "hi club", 100))
		b.handleMessage(textEvent(work, aliceLID, "w1", "hi work", 100)) // keyed by the number all the same
		return b, captureEvents(b)
	}
	contactEvent := func(jid types.JID, act *waSyncAction.ContactAction) *events.Contact {
		// What whatsmeow does before dispatching the event.
		return &events.Contact{JID: jid, Timestamp: time.Unix(200, 0), Action: act}
	}

	t.Run("renamed under the LID", func(t *testing.T) {
		b, sent := setup(t)
		b.client.Store.Contacts.PutContactName(ctx, aliceLID, "Alice", "Alice Smith")
		b.HandleEvent(contactEvent(aliceLID, &waSyncAction.ContactAction{FullName: s("Alice Smith"), FirstName: s("Alice")}))

		want := rankedName{name: "Alice Smith", rank: rankContact}
		if got := conversationRank(t, b, alice.String()); got != want {
			t.Errorf("DM = %+v, want %+v", got, want)
		}
		for _, g := range []types.JID{club, work} {
			if got := b.storedParticipantName(g.String(), alice.String()); got != want {
				t.Errorf("member in %s = %+v, want %+v", g, got, want)
			}
		}
		got := sent.ofType("names_changed")
		if len(got) != 1 || !hasJID(got[0], alice.String()) || !hasJID(got[0], club.String()) || !hasJID(got[0], work.String()) {
			t.Errorf("names_changed = %v, want one naming the DM and both groups", got)
		}
	})

	// A contact WhatsApp re-indexed from number to LID leaves a stale entry
	// under the number (whatsmeow drops the removal).
	t.Run("a stale entry under the number never wins", func(t *testing.T) {
		b, _ := setup(t)
		b.client.Store.Contacts.PutContactName(ctx, alice, "Al", "Alice Old")
		b.client.Store.Contacts.PutContactName(ctx, aliceLID, "Alice", "Alice New")
		b.HandleEvent(contactEvent(aliceLID, &waSyncAction.ContactAction{FullName: s("Alice New"), FirstName: s("Alice")}))
		if got := conversationName(t, b, alice.String()); got != "Alice New" {
			t.Errorf("DM = %q, want the edited name", got)
		}
		b.reconcile()
		if got := conversationName(t, b, alice.String()); got != "Alice New" {
			t.Errorf("DM = %q after the reconcile, want the edited name kept", got)
		}
	})

	t.Run("the action maps a LID whatsmeow can't yet", func(t *testing.T) {
		b := newClientBridge(t)
		bob := types.NewJID("447700900002", types.DefaultUserServer)
		bobLID := types.NewJID("55667788", types.HiddenUserServer)
		b.handleMessage(textEvent(bob, bob, "d1", "hi", 100))
		b.client.Store.Contacts.PutContactName(ctx, bobLID, "Bob", "Bob Jones")
		b.HandleEvent(contactEvent(bobLID, &waSyncAction.ContactAction{FullName: s("Bob Jones"), PnJID: s(bob.String())}))

		if got := conversationName(t, b, bob.String()); got != "Bob Jones" {
			t.Errorf("DM = %q, want the address-book name", got)
		}
	})
}

// A new push name renames the person wherever only a push name named them,
// and nowhere an address-book name does.
func TestHandlePushName_renamesOnlyWeakerNames(t *testing.T) {
	ctx := context.Background()
	b := newClientBridge(t)
	dave := types.NewJID("447700900004", types.DefaultUserServer)
	carol := types.NewJID("447700900003", types.DefaultUserServer)
	club := types.NewJID("120363000000000001", types.GroupServer)
	b.client.Store.Contacts.PutContactName(ctx, carol, "Carol", "Carol Smith")
	for _, m := range []struct {
		chat, sender types.JID
		id, push     string
	}{{dave, dave, "d1", "Dave"}, {club, dave, "c1", "Dave"}, {carol, carol, "d2", "caz"}, {club, carol, "c2", "caz"}} {
		evt := textEvent(m.chat, m.sender, m.id, "hi", 100)
		evt.Info.PushName = m.push
		b.handleMessage(evt)
	}
	sent := captureEvents(b)

	b.client.Store.Contacts.PutPushName(ctx, dave, "Dave G")
	b.HandleEvent(&events.PushName{JID: dave, OldPushName: "Dave", NewPushName: "Dave G"})
	b.client.Store.Contacts.PutPushName(ctx, carol, "cazza")
	b.HandleEvent(&events.PushName{JID: carol, OldPushName: "caz", NewPushName: "cazza"})

	if got := conversationName(t, b, dave.String()); got != "Dave G" {
		t.Errorf("Dave's DM = %q", got)
	}
	if got := participantNames(t, b, club.String()); got[dave.String()] != "Dave G" || got[carol.String()] != "Carol Smith" {
		t.Errorf("members = %v, want Dave renamed and Carol's address-book name kept", got)
	}
	if got := conversationName(t, b, carol.String()); got != "Carol Smith" {
		t.Errorf("Carol's DM = %q, want her address-book name kept", got)
	}
	got := sent.ofType("names_changed")
	if len(got) != 1 || !hasJID(got[0], dave.String()) || !hasJID(got[0], club.String()) || hasJID(got[0], carol.String()) {
		t.Errorf("names_changed = %v, want only Dave's change", got)
	}
}

// The reconcile upgrades names from the contact store, never downgrades
// them, and says which chats it renamed.
func TestReconcile_upgradesWithoutDowngrading(t *testing.T) {
	ctx := context.Background()
	b := newClientBridge(t)
	alice := types.NewJID("447700900001", types.DefaultUserServer)
	shop := types.NewJID("447700900099", types.DefaultUserServer)
	club := types.NewJID("120363000000000001", types.GroupServer)
	b.handleMessage(textEvent(alice, alice, "d1", "hi", 100)) // nothing known yet
	b.handleMessage(textEvent(club, alice, "c1", "hi", 100))
	b.handleMessage(verifiedEvent(shop, "s1", "acme help", "ACME Ltd"))
	// A full sync stores contacts with no event each.
	b.client.Store.Contacts.PutContactName(ctx, alice, "Alice", "Alice Smith")
	b.client.Store.Contacts.PutPushName(ctx, shop, "acme support")
	sent := captureEvents(b)

	b.reconcile()

	if got := conversationName(t, b, alice.String()); got != "Alice Smith" {
		t.Errorf("DM = %q", got)
	}
	if got := participantNames(t, b, club.String())[alice.String()]; got != "Alice Smith" {
		t.Errorf("member = %q", got)
	}
	if got := conversationName(t, b, shop.String()); got != "ACME Ltd" {
		t.Errorf("business DM = %q, want the verified name kept", got)
	}
	got := sent.ofType("names_changed")
	if len(got) != 1 || !hasJID(got[0], alice.String()) || !hasJID(got[0], club.String()) || hasJID(got[0], shop.String()) {
		t.Errorf("names_changed = %v", got)
	}
}
