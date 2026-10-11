package main

import (
	"context"
	"testing"
	"time"

	waBinary "go.mau.fi/whatsmeow/binary"
	"go.mau.fi/whatsmeow/proto/waCommon"
	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/proto/waWeb"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

var (
	caller    = types.NewJID("447700900001", types.DefaultUserServer)
	callerLID = types.NewJID("11223344", types.HiddenUserServer)
	ownPhone  = types.JID{User: ownJID.User, Server: types.DefaultUserServer, Device: 1}
	group     = types.NewJID("120363000000000001", types.GroupServer)
	callTime  = time.Unix(1_900_000_000, 0)
)

func callMeta(id string, from, creator types.JID) types.BasicCallMeta {
	return types.BasicCallMeta{From: from, Timestamp: callTime, CallCreator: creator, CallID: id}
}

func voiceOffer() *waBinary.Node {
	return &waBinary.Node{Tag: "offer", Content: []waBinary.Node{{Tag: "audio"}, {Tag: "net"}}}
}

func videoOffer() *waBinary.Node {
	return &waBinary.Node{Tag: "offer", Content: []waBinary.Node{{Tag: "audio"}, {Tag: "video"}, {Tag: "net"}}}
}

func callRow(t *testing.T, b *Bridge, callID string) (chat, text, status string) {
	t.Helper()
	err := b.msgDB.QueryRow(`SELECT conversation_jid, text_body, status FROM messages WHERE id = ? AND content_type = 'call'`,
		callRowID(callID)).Scan(&chat, &text, &status)
	if err != nil {
		t.Fatalf("call row %s: %v", callID, err)
	}
	return
}

func TestOfferIsVideo(t *testing.T) {
	if !offerIsVideo(videoOffer()) {
		t.Error("offer with a <video> child should be video")
	}
	if offerIsVideo(voiceOffer()) {
		t.Error("audio-only offer should be voice")
	}
	if offerIsVideo(nil) {
		t.Error("nil offer should be voice")
	}
}

func TestCall_missed(t *testing.T) {
	b := newClientBridge(t)
	sent := captureEvents(b)
	b.UpsertConversation(caller.String(), named("Alice"), "", 0)

	b.HandleEvent(&events.CallOffer{BasicCallMeta: callMeta("c1", caller, caller), Data: voiceOffer()})

	ringing := sent.ofType("call_incoming")
	if len(ringing) != 1 || ringing[0]["conversation_jid"] != caller.String() || ringing[0]["caller_name"] != "Alice" ||
		ringing[0]["is_video"] != false || ringing[0]["call_id"] != "c1" || ringing[0]["is_group"] != false {
		t.Fatalf("call_incoming = %v", ringing)
	}

	b.HandleEvent(&events.CallTerminate{BasicCallMeta: callMeta("c1", caller, caller), Reason: "timeout"})

	chat, text, _ := callRow(t, b, "c1")
	if chat != caller.String() || text != "Missed voice call" {
		t.Errorf("row = (%s, %q)", chat, text)
	}
	if got := unreadCount(t, b, caller.String()); got != 1 {
		t.Errorf("unread = %d, want 1", got)
	}
	if missed := sent.ofType("call_missed"); len(missed) != 1 || missed[0]["caller_name"] != "Alice" {
		t.Errorf("call_missed = %v", missed)
	}

	// Redelivered stanzas change nothing: no second row, count or ring.
	b.HandleEvent(&events.CallTerminate{BasicCallMeta: callMeta("c1", caller, caller)})
	b.HandleEvent(&events.CallOffer{BasicCallMeta: callMeta("c1", caller, caller), Data: voiceOffer()})
	if got := unreadCount(t, b, caller.String()); got != 1 {
		t.Errorf("unread after redelivery = %d, want 1", got)
	}
	if n := len(sent.ofType("call_incoming")); n != 1 {
		t.Errorf("a replayed offer rang again (%d call_incoming)", n)
	}
}

func TestCall_missedFromNewNumberCreatesChat(t *testing.T) {
	b := newClientBridge(t)
	b.HandleEvent(&events.CallOffer{BasicCallMeta: callMeta("c1", caller, caller), Data: videoOffer()})
	b.HandleEvent(&events.CallTerminate{BasicCallMeta: callMeta("c1", caller, caller)})

	_, text, _ := callRow(t, b, "c1")
	if text != "Missed video call" {
		t.Errorf("text = %q", text)
	}
	var lastID string
	var isGroup int
	b.msgDB.QueryRow(`SELECT last_message_id, is_group FROM conversations WHERE jid = ?`, caller.String()).Scan(&lastID, &isGroup)
	if lastID != callRowID("c1") || isGroup != 0 {
		t.Errorf("conversation = (%q, is_group %d), want the call as its last message in a DM", lastID, isGroup)
	}
}

func TestCall_answeredElsewhere(t *testing.T) {
	b := newClientBridge(t)
	sent := captureEvents(b)
	b.HandleEvent(&events.CallOffer{BasicCallMeta: callMeta("c1", caller, caller), Data: videoOffer()})
	b.HandleEvent(&events.CallAccept{BasicCallMeta: callMeta("c1", ownPhone, caller)})

	_, text, status := callRow(t, b, "c1")
	if text != "Video call" || status != "read" {
		t.Errorf("row = (%q, %s), want an answered, read video call", text, status)
	}
	if ended := sent.ofType("call_ended"); len(ended) != 1 || ended[0]["answered"] != true {
		t.Errorf("call_ended = %v", ended)
	}

	// Hanging up later is not a missed call.
	b.HandleEvent(&events.CallTerminate{BasicCallMeta: callMeta("c1", caller, caller)})
	if n := len(sent.ofType("call_missed")); n != 0 {
		t.Errorf("answered call reported missed")
	}
	if got := unreadCount(t, b, caller.String()); got != 0 {
		t.Errorf("unread = %d, want 0", got)
	}
}

func TestCall_declinedOnPhone(t *testing.T) {
	b := newClientBridge(t)
	sent := captureEvents(b)
	b.HandleEvent(&events.CallOffer{BasicCallMeta: callMeta("c1", caller, caller), Data: voiceOffer()})
	b.HandleEvent(&events.CallReject{BasicCallMeta: callMeta("c1", ownPhone, caller)})

	_, text, _ := callRow(t, b, "c1")
	if text != "Declined voice call" {
		t.Errorf("text = %q", text)
	}
	if got := unreadCount(t, b, caller.String()); got != 0 {
		t.Errorf("unread = %d; the user declined it themselves", got)
	}
	if ended := sent.ofType("call_ended"); len(ended) != 1 || ended[0]["answered"] != false {
		t.Errorf("call_ended = %v", ended)
	}
	if n := len(sent.ofType("call_missed")); n != 0 {
		t.Errorf("declined call reported missed")
	}
}

func TestCall_LIDCallerResolvesToPhoneChat(t *testing.T) {
	b := newClientBridge(t)
	sent := captureEvents(b)
	b.client.Store.LIDs.PutLIDMapping(context.Background(), callerLID, caller)

	b.HandleEvent(&events.CallOffer{BasicCallMeta: callMeta("c1", callerLID, callerLID), Data: voiceOffer()})

	if ringing := sent.ofType("call_incoming"); len(ringing) != 1 || ringing[0]["conversation_jid"] != caller.String() {
		t.Errorf("call_incoming = %v, want the phone-number chat", ringing)
	}
}

func TestCall_groupCall(t *testing.T) {
	b := newClientBridge(t)
	sent := captureEvents(b)
	b.UpsertConversation(group.String(), rankedName{name: "Book club", rank: rankSubject}, "", 0)
	other := types.NewJID("447700900002", types.DefaultUserServer)
	meta := func(from types.JID) types.BasicCallMeta {
		m := callMeta("g1", from, caller)
		m.GroupJID = group
		return m
	}

	b.HandleEvent(&events.CallOfferNotice{BasicCallMeta: meta(caller), Media: "video", Type: "group"})
	if ringing := sent.ofType("call_incoming"); len(ringing) != 1 || ringing[0]["conversation_jid"] != group.String() ||
		ringing[0]["caller_name"] != "Book club" || ringing[0]["is_group"] != true || ringing[0]["is_video"] != true {
		t.Fatalf("call_incoming = %v", ringing)
	}

	// Other members answering, declining or leaving doesn't end it for us.
	b.HandleEvent(&events.CallAccept{BasicCallMeta: meta(other)})
	b.HandleEvent(&events.CallReject{BasicCallMeta: meta(other)})
	b.HandleEvent(&events.CallTerminate{BasicCallMeta: meta(other)})
	if n := len(sent.ofType("call_ended")) + len(sent.ofType("call_missed")); n != 0 {
		t.Fatalf("another member's actions ended our call (%d events)", n)
	}

	b.HandleEvent(&events.CallTerminate{BasicCallMeta: meta(caller)})
	_, text, _ := callRow(t, b, "g1")
	if text != "Missed group call" {
		t.Errorf("text = %q", text)
	}
	if got := unreadCount(t, b, group.String()); got != 1 {
		t.Errorf("unread = %d, want 1", got)
	}
}

func TestCall_placedByUserIsIgnored(t *testing.T) {
	b := newClientBridge(t)
	sent := captureEvents(b)
	b.HandleEvent(&events.CallOffer{BasicCallMeta: callMeta("c1", ownPhone, ownJID), Data: voiceOffer()})
	b.HandleEvent(&events.CallTerminate{BasicCallMeta: callMeta("c1", caller, ownJID)})
	if len(sent.all) != 0 {
		t.Errorf("the user's own outgoing call produced events: %v", sent.all)
	}
}

func TestHistorySync_missedCallStubs(t *testing.T) {
	b := newClientBridge(t)
	b.retention = RetentionConfig{MaxMessages: 50}
	stub := func(id string, typ waWeb.WebMessageInfo_StubType) *waHistorySync.HistorySyncMsg {
		return &waHistorySync.HistorySyncMsg{Message: &waWeb.WebMessageInfo{
			Key:              &waCommon.MessageKey{RemoteJID: proto.String(caller.String()), ID: proto.String(id)},
			MessageTimestamp: proto.Uint64(1_900_000_000),
			MessageStubType:  typ.Enum(),
		}}
	}
	b.handleHistorySync(&events.HistorySync{Data: &waHistorySync.HistorySync{
		Conversations: []*waHistorySync.Conversation{{
			ID: proto.String(caller.String()),
			Messages: []*waHistorySync.HistorySyncMsg{
				stub("s1", waWeb.WebMessageInfo_CALL_MISSED_VIDEO),
				stub("s2", waWeb.WebMessageInfo_E2E_ENCRYPTED), // not a call: skipped
			},
		}},
	}})

	_, text, status := callRow(t, b, "s1")
	if text != "Missed video call" || status != "read" {
		t.Errorf("history call row = (%q, %s)", text, status)
	}
	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages`); n != 1 {
		t.Errorf("stored %d rows, want only the call", n)
	}
}

func TestWithoutCallRows(t *testing.T) {
	b := newTestBridge(t)
	b.UpsertConversation(caller.String(), named("Alice"), "", 0)
	b.InsertMessages([]*MessageRow{
		{ID: "m1", ConversationJID: caller.String(), SenderJID: caller.String(), Timestamp: 1, ContentType: "text"},
		{ID: "log1", ConversationJID: caller.String(), SenderJID: caller.String(), Timestamp: 2, ContentType: "call"},
	})
	got := b.WithoutCallRows([]string{"m1", "log1", "call_c1"})
	if len(got) != 1 || got[0] != "m1" {
		t.Errorf("WithoutCallRows = %v, want [m1]", got)
	}
}

// A chat whose only unread rows are missed calls is marked read like any
// other, without asking WhatsApp for a receipt on Photon's own call ids.
func TestMarkRead_callsOnlyChat(t *testing.T) {
	b := newClientBridge(t)
	b.HandleEvent(&events.CallOffer{BasicCallMeta: callMeta("c1", caller, caller), Data: voiceOffer()})
	b.HandleEvent(&events.CallTerminate{BasicCallMeta: callMeta("c1", caller, caller)})
	if got := unreadCount(t, b, caller.String()); got != 1 {
		t.Fatalf("unread = %d, want 1 missed call", got)
	}

	resp, err := b.handleMarkRead(context.Background(), mustMarshal(MarkReadPayload{
		JID: caller.String(), MessageIDs: []string{callRowID("c1")},
	}))
	if err != nil {
		t.Fatalf("mark_read: %v (no receipt should have been attempted)", err)
	}
	if string(resp) != "{}" {
		t.Errorf("response = %s", resp)
	}
	if got := unreadCount(t, b, caller.String()); got != 0 {
		t.Errorf("unread = %d, want 0", got)
	}
	if got := messageStatus(t, b, callRowID("c1")); got != "read" {
		t.Errorf("call row status = %q, want read", got)
	}
}

func callLogEvent(chat, sender types.JID, id string, ts int64) *events.Message {
	return messageEvent(chat, sender, id, ts, &waE2E.Message{
		CallLogMesssage: &waE2E.CallLogMessage{CallOutcome: waE2E.CallLogMessage_MISSED.Enum()},
	})
}

// A call seen ring and end, then logged in the chat by the phone, is one row.
func TestCall_logMessageForARecordedCallIsNotADuplicate(t *testing.T) {
	b := newClientBridge(t)
	b.HandleEvent(&events.CallOffer{BasicCallMeta: callMeta("c1", caller, caller), Data: voiceOffer()})
	b.HandleEvent(&events.CallTerminate{BasicCallMeta: callMeta("c1", caller, caller)})
	b.handleMessage(callLogEvent(caller, caller, "c1", callTime.Unix()+30))

	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages WHERE content_type = 'call'`); n != 1 {
		t.Errorf("call rows = %d, want 1", n)
	}
	if got := unreadCount(t, b, caller.String()); got != 1 {
		t.Errorf("unread = %d, want the one missed call", got)
	}

	// The log entry first (Photon missed the ringing), then the call events.
	b.handleMessage(callLogEvent(caller, caller, "c2", callTime.Unix()+100))
	b.HandleEvent(&events.CallOffer{BasicCallMeta: callMeta("c2", caller, caller), Data: voiceOffer()})
	b.HandleEvent(&events.CallTerminate{BasicCallMeta: callMeta("c2", caller, caller)})
	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages WHERE content_type = 'call'`); n != 2 {
		t.Errorf("call rows = %d, want 2 (one per call)", n)
	}
}
