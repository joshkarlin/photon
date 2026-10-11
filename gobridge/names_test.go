package main

import (
	"context"
	"testing"

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
		b.UpsertConversation(shop.String(), "acme help", false, "m1", 100)

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
		b.UpsertConversation(shop.String(), "Corner Shop", false, "m1", 100)

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
	b.UpsertConversation(lid.String(), "", false, "m1", 100)

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
