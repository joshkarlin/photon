package main

import (
	"context"
	"encoding/json"
	"errors"
	"reflect"
	"testing"

	"go.mau.fi/whatsmeow/types"
)

// answers makes the bridge's WhatsApp lookup reply from known, recording
// what it was asked. The client is offline, so nothing else could answer.
func answers(b *Bridge, known map[string]types.IsOnWhatsAppResponse) *[][]string {
	var asked [][]string
	b.isOnWhatsApp = func(_ context.Context, phones []string) ([]types.IsOnWhatsAppResponse, error) {
		asked = append(asked, phones)
		var out []types.IsOnWhatsAppResponse
		for _, p := range phones {
			if r, ok := known[p]; ok {
				r.Query = p
				out = append(out, r)
			}
		}
		return out, nil
	}
	return &asked
}

func pnJID(digits string) types.JID { return types.NewJID(digits, types.DefaultUserServer) }

// chatWith stores a chat with one message.
func chatWith(t *testing.T, b *Bridge, jid string) {
	t.Helper()
	if _, err := b.InsertMessage(&MessageRow{ID: "m-" + jid, ConversationJID: jid, SenderJID: jid, Timestamp: 1_900_000_000, ContentType: "text"}); err != nil {
		t.Fatal(err)
	}
	b.UpsertConversation(jid, named("Someone"), "m-"+jid, 1_900_000_000)
}

func TestCheckOnWhatsApp_aNumberWithAChatIsAnsweredWithoutAsking(t *testing.T) {
	b := newClientBridge(t)
	chatWith(t, b, "447700900123@s.whatsapp.net")
	asked := answers(b, nil)

	got, err := b.CheckOnWhatsApp(context.Background(), []string{"+44 7700 900123"})
	if err != nil {
		t.Fatal(err)
	}

	want := []PhoneReach{{Phone: "+44 7700 900123", JID: "447700900123@s.whatsapp.net", OnWhatsApp: true, Existing: true}}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got %+v, want %+v", got, want)
	}
	if len(*asked) != 0 {
		t.Errorf("asked WhatsApp about %v", *asked)
	}
}

// A chat still keyed by the person's LID is theirs: it's merged into the
// phone JID's row, which is what sends key by, and that's opened.
func TestCheckOnWhatsApp_aChatUnderTheirLIDIsMergedAndOpened(t *testing.T) {
	b := newClientBridge(t)
	events := captureEvents(b)
	lid := types.NewJID("99887766", types.HiddenUserServer)
	b.client.Store.LIDs.PutLIDMapping(context.Background(), lid, pnJID("447700900123"))
	chatWith(t, b, lid.String())
	answers(b, nil)

	got, err := b.CheckOnWhatsApp(context.Background(), []string{"+447700900123"})
	if err != nil {
		t.Fatal(err)
	}

	if got[0].JID != "447700900123@s.whatsapp.net" || !got[0].Existing {
		t.Errorf("got %+v, want the phone JID's existing chat", got[0])
	}
	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages WHERE conversation_jid = '447700900123@s.whatsapp.net'`); n != 1 {
		t.Errorf("%d messages moved to the phone JID, want 1", n)
	}
	if n := rowCount(t, b, `SELECT COUNT(*) FROM conversations WHERE jid = ?`, lid.String()); n != 0 {
		t.Error("the LID row is still there")
	}
	if len(events.ofType("conversation_updated")) == 0 {
		t.Error("the merge wasn't announced")
	}
}

func TestCheckOnWhatsApp_asksWhatsAppAboutTheRestInOneQuery(t *testing.T) {
	b := newClientBridge(t)
	asked := answers(b, map[string]types.IsOnWhatsAppResponse{
		// On WhatsApp under a LID, with the phone number it maps to.
		"+447700900123": {IsIn: true, JID: types.NewJID("11223344", types.HiddenUserServer), PhoneNumber: pnJID("447700900123")},
		"+447700900999": {IsIn: false},
		// WhatsApp knows the number in another form (as with some mobile
		// number formats), under a phone JID.
		"+5511987654321": {IsIn: true, JID: pnJID("551187654321")},
	})

	got, err := b.CheckOnWhatsApp(context.Background(), []string{
		"+447700900123", "+447700900999", "+5511987654321", "+447700900000", "+44 7700 900123", "12",
	})
	if err != nil {
		t.Fatal(err)
	}

	want := []PhoneReach{
		{Phone: "+447700900123", JID: "447700900123@s.whatsapp.net", OnWhatsApp: true},
		{Phone: "+447700900999"},
		{Phone: "+5511987654321", JID: "551187654321@s.whatsapp.net", OnWhatsApp: true},
		// WhatsApp didn't answer for it: not on WhatsApp.
		{Phone: "+447700900000"},
		{Phone: "+44 7700 900123", JID: "447700900123@s.whatsapp.net", OnWhatsApp: true},
		// Not a number: never asked about.
		{Phone: "12"},
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got  %+v\nwant %+v", got, want)
	}
	wantAsked := [][]string{{"+447700900123", "+447700900999", "+5511987654321", "+447700900000"}}
	if !reflect.DeepEqual(*asked, wantAsked) {
		t.Errorf("asked %v, want %v", *asked, wantAsked)
	}
}

// WhatsApp's form of the number may already have a chat.
func TestCheckOnWhatsApp_WhatsAppsFormOfTheNumberMayHaveAChat(t *testing.T) {
	b := newClientBridge(t)
	chatWith(t, b, "551187654321@s.whatsapp.net")
	answers(b, map[string]types.IsOnWhatsAppResponse{
		"+5511987654321": {IsIn: true, JID: pnJID("551187654321")},
	})

	got, err := b.CheckOnWhatsApp(context.Background(), []string{"+5511987654321"})
	if err != nil {
		t.Fatal(err)
	}

	if got[0].JID != "551187654321@s.whatsapp.net" || !got[0].Existing {
		t.Errorf("got %+v, want the existing chat", got[0])
	}
}

// Offline, a number without a chat can't be checked: unknown, not "no".
func TestCheckOnWhatsApp_offlineIsAnErrorUnlessTheChatExists(t *testing.T) {
	b := newClientBridge(t) // offline: isOnWhatsApp is nil, so the client answers
	chatWith(t, b, "447700900123@s.whatsapp.net")

	if _, err := b.CheckOnWhatsApp(context.Background(), []string{"+447700900999"}); !errors.Is(err, errNotConnected) {
		t.Errorf("err = %v, want %v", err, errNotConnected)
	}
	got, err := b.CheckOnWhatsApp(context.Background(), []string{"+447700900123"})
	if err != nil || !got[0].Existing {
		t.Errorf("got %+v, %v; want the existing chat", got, err)
	}
}

func TestCheckOnWhatsApp_lookupFailures(t *testing.T) {
	b := newClientBridge(t)
	b.isOnWhatsApp = func(context.Context, []string) ([]types.IsOnWhatsAppResponse, error) {
		return nil, errors.New("usync timed out")
	}
	if _, err := b.CheckOnWhatsApp(context.Background(), []string{"+447700900999"}); err == nil {
		t.Error("a failed lookup answered")
	}

	// Answers whose LID mappings couldn't be stored still answer.
	b.isOnWhatsApp = func(_ context.Context, phones []string) ([]types.IsOnWhatsAppResponse, error) {
		return []types.IsOnWhatsAppResponse{{Query: phones[0], IsIn: true, JID: pnJID("447700900999")}}, errors.New("failed to store LID mappings")
	}
	got, err := b.CheckOnWhatsApp(context.Background(), []string{"+447700900999"})
	if err != nil || !got[0].OnWhatsApp {
		t.Errorf("got %+v, %v", got, err)
	}
}

// The request as Kotlin sends it, and its response.
func TestHandleRequest_checkOnWhatsApp(t *testing.T) {
	b := newClientBridge(t)
	answers(b, map[string]types.IsOnWhatsAppResponse{"+447700900123": {IsIn: true, JID: pnJID("447700900123")}})

	resp := b.handleRequest(context.Background(), Envelope{
		ID: "r1", Type: "check_on_whatsapp", Payload: json.RawMessage(`{"phones":["+447700900123"]}`),
	})

	if resp.ID != "r1" || resp.Type != "check_on_whatsapp_response" {
		t.Errorf("envelope = %s %s", resp.ID, resp.Type)
	}
	want := `{"results":[{"phone":"+447700900123","jid":"447700900123@s.whatsapp.net","on_whatsapp":true,"existing":false}]}`
	if string(resp.Payload) != want {
		t.Errorf("payload = %s\nwant      %s", resp.Payload, want)
	}

	b.isOnWhatsApp = nil // offline
	resp = b.handleRequest(context.Background(), Envelope{
		ID: "r2", Type: "check_on_whatsapp", Payload: json.RawMessage(`{"phones":["+447700900999"]}`),
	})
	if string(resp.Payload) != `{"error":"WhatsApp disconnected"}` {
		t.Errorf("offline payload = %s", resp.Payload)
	}
}
