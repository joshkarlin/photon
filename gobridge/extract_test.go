package main

import (
	"testing"

	"go.mau.fi/whatsmeow/proto/waCommon"
	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/proto/waWeb"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

var s = proto.String

func wrap(inner *waE2E.Message) *waE2E.FutureProofMessage {
	return &waE2E.FutureProofMessage{Message: inner}
}

func hydratedTemplate(title, body, footer string) *waE2E.TemplateMessage {
	return &waE2E.TemplateMessage{Format: &waE2E.TemplateMessage_HydratedFourRowTemplate_{
		HydratedFourRowTemplate: &waE2E.TemplateMessage_HydratedFourRowTemplate{
			Title:               &waE2E.TemplateMessage_HydratedFourRowTemplate_HydratedTitleText{HydratedTitleText: title},
			HydratedContentText: s(body),
			HydratedFooterText:  s(footer),
		},
	}}
}

func TestExtractContent(t *testing.T) {
	poll := &waE2E.PollCreationMessage{
		Name:    s("Lunch?"),
		Options: []*waE2E.PollCreationMessage_Option{{OptionName: s("Pizza")}, {OptionName: s("Sushi")}},
	}
	const pollText = "Poll: Lunch?\n- Pizza\n- Sushi"

	cases := []struct {
		name     string
		msg      *waE2E.Message
		wantType string
		wantText string
	}{
		{"plain text", &waE2E.Message{Conversation: s("hi")}, "text", "hi"},
		{"extended text", &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{Text: s("hi")}}, "text", "hi"},
		{"image caption", &waE2E.Message{ImageMessage: &waE2E.ImageMessage{Caption: s("look")}}, "image", "look"},
		{"video note (PTV) is a video", &waE2E.Message{PtvMessage: &waE2E.VideoMessage{}}, "video", ""},

		// Business messages.
		{"hydrated template", &waE2E.Message{TemplateMessage: hydratedTemplate("Your order", "Order #12 has shipped", "Reply STOP to opt out")},
			"text", "Your order\nOrder #12 has shipped\nReply STOP to opt out"},
		{"legacy hydratedTemplate field", &waE2E.Message{TemplateMessage: &waE2E.TemplateMessage{
			HydratedTemplate: &waE2E.TemplateMessage_HydratedFourRowTemplate{HydratedContentText: s("Your code is 1234")},
		}}, "text", "Your code is 1234"},
		{"four-row template", &waE2E.Message{TemplateMessage: &waE2E.TemplateMessage{Format: &waE2E.TemplateMessage_FourRowTemplate_{
			FourRowTemplate: &waE2E.TemplateMessage_FourRowTemplate{
				Content: &waE2E.HighlyStructuredMessage{HydratedHsm: hydratedTemplate("", "Boarding pass ready", "")},
			},
		}}}, "text", "Boarding pass ready"},
		{"interactive template", &waE2E.Message{TemplateMessage: &waE2E.TemplateMessage{Format: &waE2E.TemplateMessage_InteractiveMessageTemplate{
			InteractiveMessageTemplate: &waE2E.InteractiveMessage{Body: &waE2E.InteractiveMessage_Body{Text: s("Rate your stay")}},
		}}}, "text", "Rate your stay"},
		{"interactive", &waE2E.Message{InteractiveMessage: &waE2E.InteractiveMessage{
			Header: &waE2E.InteractiveMessage_Header{Title: s("Delivery")},
			Body:   &waE2E.InteractiveMessage_Body{Text: s("Arriving 2-4pm")},
			Footer: &waE2E.InteractiveMessage_Footer{Text: s("Track in app")},
		}}, "text", "Delivery\nArriving 2-4pm\nTrack in app"},
		{"interactive carousel", &waE2E.Message{InteractiveMessage: &waE2E.InteractiveMessage{
			InteractiveMessage: &waE2E.InteractiveMessage_CarouselMessage_{CarouselMessage: &waE2E.InteractiveMessage_CarouselMessage{
				Cards: []*waE2E.InteractiveMessage{
					{Body: &waE2E.InteractiveMessage_Body{Text: s("Card one")}},
					{Body: &waE2E.InteractiveMessage_Body{Text: s("Card two")}},
				},
			}},
		}}, "text", "Card one\n\nCard two"},
		{"buttons", &waE2E.Message{ButtonsMessage: &waE2E.ButtonsMessage{
			Header:      &waE2E.ButtonsMessage_Text{Text: "Appointment"},
			ContentText: s("Confirm for Tuesday?"),
		}}, "text", "Appointment\nConfirm for Tuesday?"},
		{"list", &waE2E.Message{ListMessage: &waE2E.ListMessage{Title: s("Menu"), Description: s("Pick a dish")}}, "text", "Menu\nPick a dish"},
		{"highly structured", &waE2E.Message{HighlyStructuredMessage: &waE2E.HighlyStructuredMessage{
			HydratedHsm: hydratedTemplate("", "Your OTP is 99", ""),
		}}, "text", "Your OTP is 99"},
		{"empty business message still shows", &waE2E.Message{InteractiveMessage: &waE2E.InteractiveMessage{}}, "text", businessText},

		// Replies to business messages.
		{"buttons response", &waE2E.Message{ButtonsResponseMessage: &waE2E.ButtonsResponseMessage{
			Response: &waE2E.ButtonsResponseMessage_SelectedDisplayText{SelectedDisplayText: "Yes, confirm"},
		}}, "text", "Yes, confirm"},
		{"list response", &waE2E.Message{ListResponseMessage: &waE2E.ListResponseMessage{Title: s("Margherita")}}, "text", "Margherita"},
		{"template button reply", &waE2E.Message{TemplateButtonReplyMessage: &waE2E.TemplateButtonReplyMessage{SelectedDisplayText: s("Track")}}, "text", "Track"},
		{"interactive response", &waE2E.Message{InteractiveResponseMessage: &waE2E.InteractiveResponseMessage{
			Body: &waE2E.InteractiveResponseMessage_Body{Text: s("5 stars")},
		}}, "text", "5 stars"},

		// Polls in every version.
		{"poll v1", &waE2E.Message{PollCreationMessage: poll}, "text", pollText},
		{"poll v2", &waE2E.Message{PollCreationMessageV2: poll}, "text", pollText},
		{"poll v3", &waE2E.Message{PollCreationMessageV3: poll}, "text", pollText},
		{"poll v4 (wrapped)", &waE2E.Message{PollCreationMessageV4: wrap(&waE2E.Message{PollCreationMessage: poll})}, "text", pollText},
		{"poll v5", &waE2E.Message{PollCreationMessageV5: poll}, "text", pollText},
		{"poll v6", &waE2E.Message{PollCreationMessageV6: poll}, "text", pollText},

		{"event", &waE2E.Message{EventMessage: &waE2E.EventMessage{Name: s("Picnic"), Description: s("Bring food")}}, "text", "Event: Picnic\nBring food"},
		{"cancelled event", &waE2E.Message{EventMessage: &waE2E.EventMessage{Name: s("Picnic"), IsCanceled: proto.Bool(true)}}, "text", "Event: Picnic (cancelled)"},
		{"contacts array", &waE2E.Message{ContactsArrayMessage: &waE2E.ContactsArrayMessage{
			Contacts: []*waE2E.ContactMessage{{DisplayName: s("Ann")}, {DisplayName: s("Bo")}},
		}}, "contact", "Ann, Bo"},
		{"live location", &waE2E.Message{LiveLocationMessage: &waE2E.LiveLocationMessage{
			DegreesLatitude: proto.Float64(51.5), DegreesLongitude: proto.Float64(-0.12),
		}}, "location", "51.500000, -0.120000"},
		{"group invite", &waE2E.Message{GroupInviteMessage: &waE2E.GroupInviteMessage{GroupName: s("Book club")}}, "text", "Group invite: Book club"},
		{"product", &waE2E.Message{ProductMessage: &waE2E.ProductMessage{
			Product: &waE2E.ProductMessage_ProductSnapshot{Title: s("Mug")},
		}}, "text", "[ product ] Mug"},
		{"order", &waE2E.Message{OrderMessage: &waE2E.OrderMessage{OrderTitle: s("Groceries")}}, "text", "[ order ] Groceries"},
		{"invoice", &waE2E.Message{InvoiceMessage: &waE2E.InvoiceMessage{Note: s("March")}}, "text", "[ invoice ]\nMarch"},
		{"masked for linked devices", &waE2E.Message{PlaceholderMessage: &waE2E.PlaceholderMessage{}}, "text", phoneOnlyText},
		{"call log", &waE2E.Message{CallLogMesssage: &waE2E.CallLogMessage{
			IsVideo: proto.Bool(true), CallOutcome: waE2E.CallLogMessage_MISSED.Enum(),
		}}, "call", "Missed video call"},

		// Wrappers are looked through.
		{"group mention wrapper", &waE2E.Message{GroupMentionedMessage: wrap(&waE2E.Message{Conversation: s("@all meeting")})}, "text", "@all meeting"},
		{"album child image", &waE2E.Message{AssociatedChildMessage: wrap(&waE2E.Message{ImageMessage: &waE2E.ImageMessage{}})}, "image", ""},
		{"nested wrappers", &waE2E.Message{BotForwardedMessage: wrap(&waE2E.Message{SpoilerMessage: wrap(&waE2E.Message{Conversation: s("secret")})})}, "text", "secret"},

		// Something real Photon doesn't render yet is shown, not dropped.
		{"unknown content", &waE2E.Message{RichResponseMessage: &waE2E.AIRichResponseMessage{}}, "text", unsupportedText},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			gotType, gotText, ok := extractContent(tc.msg)
			if !ok || gotType != tc.wantType || gotText != tc.wantText {
				t.Errorf("extractContent = (%q, %q, %v), want (%q, %q, true)", gotType, gotText, ok, tc.wantType, tc.wantText)
			}
		})
	}
}

func TestExtractContent_dropsContentFree(t *testing.T) {
	cases := []struct {
		name string
		msg  *waE2E.Message
	}{
		{"nil", nil},
		{"sender key distribution", &waE2E.Message{
			SenderKeyDistributionMessage: &waE2E.SenderKeyDistributionMessage{GroupID: s("g")},
			MessageContextInfo:           &waE2E.MessageContextInfo{},
		}},
		{"app state key share", &waE2E.Message{ProtocolMessage: &waE2E.ProtocolMessage{
			Type: waE2E.ProtocolMessage_APP_STATE_SYNC_KEY_SHARE.Enum(),
		}}},
		{"poll vote", &waE2E.Message{PollUpdateMessage: &waE2E.PollUpdateMessage{}}},
		{"pin", &waE2E.Message{PinInChatMessage: &waE2E.PinInChatMessage{}}},
		{"album header", &waE2E.Message{AlbumMessage: &waE2E.AlbumMessage{}}},
		{"empty conversation", &waE2E.Message{Conversation: s("")}},
		{"empty wrapper", &waE2E.Message{GroupMentionedMessage: wrap(nil)}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if ct, txt, ok := extractContent(tc.msg); ok {
				t.Errorf("extractContent = (%q, %q, true), want dropped", ct, txt)
			}
		})
	}
}

func TestClassifyMessageEvent(t *testing.T) {
	key := &waCommon.MessageKey{ID: s("target")}
	cases := []struct {
		name       string
		msg        *waE2E.Message
		wantKind   messageKind
		wantTarget string
	}{
		{"reaction", &waE2E.Message{ReactionMessage: &waE2E.ReactionMessage{Key: key, Text: s("👍")}}, kindReaction, "target"},
		{"revoke", &waE2E.Message{ProtocolMessage: &waE2E.ProtocolMessage{Type: waE2E.ProtocolMessage_REVOKE.Enum(), Key: key}}, kindRevoke, "target"},
		{"edit", &waE2E.Message{ProtocolMessage: &waE2E.ProtocolMessage{Type: waE2E.ProtocolMessage_MESSAGE_EDIT.Enum(), Key: key}}, kindEdit, "target"},
		// A protocol message without a key is not a revoke (REVOKE is the
		// zero enum value, so an unset type must not be read as one).
		{"keyless protocol message", &waE2E.Message{ProtocolMessage: &waE2E.ProtocolMessage{}}, kindDrop, ""},
		{"content", &waE2E.Message{Conversation: s("hi")}, kindContent, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			kind, target := classifyMessageEvent(tc.msg)
			if kind != tc.wantKind || target != tc.wantTarget {
				t.Errorf("classifyMessageEvent = (%v, %q), want (%v, %q)", kind, target, tc.wantKind, tc.wantTarget)
			}
		})
	}
}

func TestGetContextInfo_anyContentType(t *testing.T) {
	ctx := &waE2E.ContextInfo{StanzaID: s("quoted")}
	for name, msg := range map[string]*waE2E.Message{
		"extended text":    {ExtendedTextMessage: &waE2E.ExtendedTextMessage{ContextInfo: ctx}},
		"buttons response": {ButtonsResponseMessage: &waE2E.ButtonsResponseMessage{ContextInfo: ctx}},
		"template":         {TemplateMessage: &waE2E.TemplateMessage{ContextInfo: ctx}},
		"poll":             {PollCreationMessageV3: &waE2E.PollCreationMessage{ContextInfo: ctx}},
	} {
		if got := getContextInfo(msg).GetStanzaID(); got != "quoted" {
			t.Errorf("%s: reply target = %q, want quoted", name, got)
		}
	}
	if getContextInfo(&waE2E.Message{Conversation: s("x")}) != nil {
		t.Error("plain conversation has no context info")
	}
}

// A business DM made only of templates used to be dropped entirely, so the
// chat never appeared in the list.
func TestHandleMessage_businessTemplateCreatesChat(t *testing.T) {
	b := newClientBridge(t)
	events := captureEvents(b)
	shop := types.NewJID("447700900099", types.DefaultUserServer)

	b.handleMessage(messageEvent(shop, shop, "t1", 100, &waE2E.Message{
		TemplateMessage: hydratedTemplate("", "Your parcel is out for delivery", ""),
	}))

	if got := textBody(t, b, "t1"); got != "Your parcel is out for delivery" {
		t.Errorf("text = %q", got)
	}
	if n := rowCount(t, b, `SELECT COUNT(*) FROM conversations WHERE jid = ? AND last_message_id = 't1'`, shop.String()); n != 1 {
		t.Errorf("business chat has no conversation row pointing at the message")
	}
	if got := unreadCount(t, b, shop.String()); got != 1 {
		t.Errorf("unread = %d, want 1", got)
	}
	if len(events.ofType("new_message")) != 1 {
		t.Errorf("no new_message for a business template")
	}
}

func TestHandleMessage_dropsSenderKeyDistribution(t *testing.T) {
	b := newClientBridge(t)
	grp := types.NewJID("120363000000000001", types.GroupServer)
	alice := types.NewJID("447700900001", types.DefaultUserServer)
	b.handleMessage(messageEvent(grp, alice, "k1", 100, &waE2E.Message{
		SenderKeyDistributionMessage: &waE2E.SenderKeyDistributionMessage{GroupID: s(grp.String())},
	}))
	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages`); n != 0 {
		t.Errorf("stored %d rows for a sender-key distribution message", n)
	}
}

func TestHandleMessage_callLogShownWithoutUnread(t *testing.T) {
	b := newClientBridge(t)
	events := captureEvents(b)
	alice := types.NewJID("447700900001", types.DefaultUserServer)
	b.handleMessage(messageEvent(alice, alice, "c1", 100, &waE2E.Message{
		CallLogMesssage: &waE2E.CallLogMessage{CallOutcome: waE2E.CallLogMessage_MISSED.Enum()},
	}))

	if got := textBody(t, b, callRowID("c1")); got != "Missed voice call" {
		t.Errorf("text = %q", got)
	}
	if got := unreadCount(t, b, alice.String()); got != 0 {
		t.Errorf("unread = %d; call events own the unread count for calls", got)
	}
	if len(events.ofType("new_message")) != 0 {
		t.Errorf("call log entries must not notify as messages")
	}
}

func TestHandleMessage_phoneResendReplacesPlaceholder(t *testing.T) {
	b := newClientBridge(t)
	shop := types.NewJID("447700900099", types.DefaultUserServer)
	b.handleMessage(messageEvent(shop, shop, "p1", 100, &waE2E.Message{PlaceholderMessage: &waE2E.PlaceholderMessage{}}))

	resend := textEvent(shop, shop, "p1", "Your code is 4242", 100)
	resend.UnavailableRequestID = "req1"
	b.handleMessage(resend)

	if got := textBody(t, b, "p1"); got != "Your code is 4242" {
		t.Errorf("text = %q, want the phone's copy", got)
	}
	if got := unreadCount(t, b, shop.String()); got != 1 {
		t.Errorf("unread = %d, want 1 (the resend is the same message)", got)
	}
}

func historyMsg(chat types.JID, id string, fromMe bool, participant string, ts uint64, msg *waE2E.Message) *waHistorySync.HistorySyncMsg {
	wm := &waWeb.WebMessageInfo{
		Key:              &waCommon.MessageKey{RemoteJID: s(chat.String()), FromMe: proto.Bool(fromMe), ID: s(id)},
		Message:          msg,
		MessageTimestamp: proto.Uint64(ts),
	}
	if participant != "" {
		wm.Participant = s(participant)
	}
	return &waHistorySync.HistorySyncMsg{Message: wm}
}

func TestHandleHistorySync_sharesLiveExtraction(t *testing.T) {
	b := newClientBridge(t)
	b.retention = RetentionConfig{MaxMessages: 50}
	alice := types.NewJID("447700900001", types.DefaultUserServer)
	now := uint64(1_900_000_000)

	b.handleHistorySync(&events.HistorySync{Data: &waHistorySync.HistorySync{
		Conversations: []*waHistorySync.Conversation{{
			ID: s(alice.String()),
			Messages: []*waHistorySync.HistorySyncMsg{
				historyMsg(alice, "h1", false, "", now, &waE2E.Message{TemplateMessage: hydratedTemplate("", "Template from history", "")}),
				// Disappearing-message wrapper: was stored as an empty row.
				historyMsg(alice, "h2", false, "", now+1, &waE2E.Message{EphemeralMessage: wrap(&waE2E.Message{Conversation: s("vanishing")})}),
				// Content-free traffic and reactions: were stored as "[ deleted ]" rows.
				historyMsg(alice, "h3", false, "", now+2, &waE2E.Message{SenderKeyDistributionMessage: &waE2E.SenderKeyDistributionMessage{}}),
				historyMsg(alice, "h4", true, "", now+3, &waE2E.Message{ReactionMessage: &waE2E.ReactionMessage{Key: &waCommon.MessageKey{ID: s("h1")}, Text: s("👍")}}),
				// An edit of h5 later in the payload.
				historyMsg(alice, "h5", true, "", now+4, &waE2E.Message{Conversation: s("teh")}),
				historyMsg(alice, "h6", true, "", now+5, &waE2E.Message{EditedMessage: wrap(editProtocol("h5", &waE2E.Message{Conversation: s("the")}))}),
			},
		}},
	}})

	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages`); n != 3 {
		t.Errorf("stored %d rows, want 3 (h1, h2, h5)", n)
	}
	if got := textBody(t, b, "h1"); got != "Template from history" {
		t.Errorf("h1 = %q", got)
	}
	if got := textBody(t, b, "h2"); got != "vanishing" {
		t.Errorf("h2 = %q, want the unwrapped ephemeral text", got)
	}
	if got := textBody(t, b, "h5"); got != "the" {
		t.Errorf("h5 = %q, want the edited text", got)
	}
	var sender string
	b.msgDB.QueryRow(`SELECT sender_jid FROM messages WHERE id = 'h5'`).Scan(&sender)
	if sender != ownJID.String() {
		t.Errorf("own history message sender = %q, want %q (as live messages store it)", sender, ownJID.String())
	}
}
