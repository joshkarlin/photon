package main

import (
	"database/sql"
	"strings"
	"sync"
	"time"

	waBinary "go.mau.fi/whatsmeow/binary"
	"go.mau.fi/whatsmeow/proto/waWeb"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

// Photon can't take WhatsApp calls; it tells the user one is ringing on their
// other devices and records how it ended. It never declines on the user's
// behalf (no RejectCall), and calls ignore mute, as WhatsApp's ringing does.

// pendingCallTTL bounds how long an offer with no terminate is remembered.
const pendingCallTTL = 10 * time.Minute

// pendingCall is a call that has rung and not yet ended.
type pendingCall struct {
	chatJID    string
	senderJID  string
	callerName string     // the caller's own name, or ""
	chatName   rankedName // the chat's name, to store with its row
	isVideo    bool
	isGroup    bool // a group call (which may not belong to a group chat)
	answered   bool
	ts         int64
	seen       time.Time
}

// callTracker holds in-flight calls by call ID, plus recently ended ones so a
// late or replayed offer can't ring again.
type callTracker struct {
	mu    sync.Mutex
	calls map[string]*pendingCall
	ended map[string]time.Time
}

func (t *callTracker) get(id string) *pendingCall {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.calls[id]
}

// add records a new call; false if the ID is already ringing (an offer and an
// offer notice can both arrive for one call).
func (t *callTracker) add(id string, c *pendingCall) bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.calls == nil {
		t.calls = make(map[string]*pendingCall)
		t.ended = make(map[string]time.Time)
	}
	for oldID, old := range t.calls {
		if time.Since(old.seen) > pendingCallTTL {
			delete(t.calls, oldID)
		}
	}
	for oldID, at := range t.ended {
		if time.Since(at) > pendingCallTTL {
			delete(t.ended, oldID)
		}
	}
	if _, ok := t.calls[id]; ok {
		return false
	}
	if _, ok := t.ended[id]; ok {
		return false
	}
	t.calls[id] = c
	return true
}

// markAnswered flags the call answered; false if it's unknown or already was.
func (t *callTracker) markAnswered(id string) bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	c := t.calls[id]
	if c == nil || c.answered {
		return false
	}
	c.answered = true
	return true
}

// end forgets a call and remembers that it ended.
func (t *callTracker) end(id string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	delete(t.calls, id)
	if t.ended != nil {
		t.ended[id] = time.Now()
	}
}

// offerIsVideo reports whether an <offer> node describes a video call: video
// offers carry a <video> codec child next to <audio>. whatsmeow doesn't parse
// this; the layout follows other WhatsApp clients' call handling and hasn't
// been confirmed against a captured video offer.
func offerIsVideo(offer *waBinary.Node) bool {
	if offer == nil {
		return false
	}
	_, ok := offer.GetOptionalChildByTag("video")
	return ok
}

// How a call ended, for its chat row.
type callOutcome int

const (
	callAnswered callOutcome = iota // picked up on another device
	callMissed
	callDeclined // declined by the user on another device
)

// callText is a call row's body.
func callText(c *pendingCall, outcome callOutcome) string {
	kind := "voice call"
	switch {
	case c.isGroup:
		kind = "group call"
	case c.isVideo:
		kind = "video call"
	}
	switch outcome {
	case callMissed:
		return "Missed " + kind
	case callDeclined:
		return "Declined " + kind
	}
	return strings.ToUpper(kind[:1]) + kind[1:]
}

func callRowID(callID string) string { return "call_" + callID }

// row is the call's row in its chat.
func (c *pendingCall) row(callID string, outcome callOutcome, status string) *MessageRow {
	return &MessageRow{
		ID:              callRowID(callID),
		ConversationJID: c.chatJID,
		SenderJID:       c.senderJID,
		Timestamp:       c.ts,
		ContentType:     "call",
		TextBody:        sql.NullString{String: callText(c, outcome), Valid: true},
		Status:          status,
	}
}

func (b *Bridge) isOwnUser(jid types.JID) bool {
	if jid.IsEmpty() || b.client.Store.ID == nil {
		return false
	}
	return jid.User == b.client.Store.ID.User || jid.User == b.client.Store.GetLID().User
}

// callerJID is the caller's canonical (phone, non-device) JID.
func (b *Bridge) callerJID(meta types.BasicCallMeta) types.JID {
	creator := meta.CallCreator
	if creator.Server == types.HiddenUserServer && meta.CallCreatorAlt.Server == types.DefaultUserServer {
		creator = meta.CallCreatorAlt
	}
	return b.resolveLIDJID(creator.ToNonAD()).ToNonAD()
}

// callChat is the conversation a call belongs to: the group chat for a call
// placed in a group, otherwise the caller's DM (ad-hoc group calls included).
func (b *Bridge) callChat(meta types.BasicCallMeta) types.JID {
	if !meta.GroupJID.IsEmpty() {
		return meta.GroupJID
	}
	return b.callerJID(meta)
}

// callChatName is the chat's name: its stored one, or, if the contact store
// or group info has a stronger one, that.
func (b *Bridge) callChatName(chat, caller types.JID) rankedName {
	stored := b.storedConversationName(chat.String())
	if chat.Server == types.GroupServer {
		if stored.name != "" {
			return stored
		}
		return rankedName{name: cleanName(b.groupName(chat)), rank: rankSubject}
	}
	return better(stored, personName(b.lookupContact(caller), "", ""))
}

// callerName is the caller's own name: the chat's for a call in their DM;
// in a group, their member name or, failing that, the contact store's.
func (b *Bridge) callerName(chat, caller types.JID, chatName rankedName) string {
	if chat.Server != types.GroupServer {
		return chatName.name
	}
	return better(b.storedParticipantName(chat.String(), caller.String()), personName(b.lookupContact(caller), "", "")).name
}

// sameUser compares two JIDs as people: device suffix ignored, LIDs resolved.
func (b *Bridge) sameUser(x, y types.JID) bool {
	if x.IsEmpty() || y.IsEmpty() {
		return false
	}
	return b.resolveLIDJID(x.ToNonAD()).User == b.resolveLIDJID(y.ToNonAD()).User
}

func (b *Bridge) handleCallEvent(evt interface{}) {
	switch v := evt.(type) {
	case *events.CallOffer:
		b.callOffered(v.BasicCallMeta, offerIsVideo(v.Data), !v.GroupJID.IsEmpty(), "offer")
	case *events.CallOfferNotice:
		b.callOffered(v.BasicCallMeta, v.Media == "video", v.Type == "group" || !v.GroupJID.IsEmpty(), "offer_notice")
	case *events.CallPreAccept:
		// A device getting ready to answer, not an answer.
		b.logCall("preaccept", v.BasicCallMeta, "")
	case *events.CallAccept:
		b.callAccepted(v.BasicCallMeta)
	case *events.CallTerminate:
		b.callEnded(v.BasicCallMeta, "terminate", v.Reason)
	case *events.CallReject:
		b.callEnded(v.BasicCallMeta, "reject", "")
	case *events.UnknownCallEvent:
		xml := v.Node.String()
		if len(xml) > 500 {
			xml = xml[:500] + "…"
		}
		b.log.Infof("Unknown call event: %s", xml)
	}
}

// logCall is the one Info line per call event, kept so on-device logs show
// which call stanzas reach a linked device.
func (b *Bridge) logCall(kind string, meta types.BasicCallMeta, extra string) {
	b.log.Infof("Call %s %s from=%s creator=%s group=%s %s", kind, meta.CallID, meta.From, meta.CallCreator, meta.GroupJID, extra)
}

func (b *Bridge) callOffered(meta types.BasicCallMeta, isVideo, isGroupCall bool, kind string) {
	b.logCall(kind, meta, "")
	if b.isOwnUser(meta.CallCreator) || b.isOwnUser(meta.CallCreatorAlt) {
		return // a call the user placed from another device
	}
	chat := b.callChat(meta)
	caller := b.callerJID(meta)
	c := &pendingCall{
		chatJID:   chat.String(),
		senderJID: caller.String(),
		chatName:  b.callChatName(chat, caller),
		isVideo:   isVideo,
		isGroup:   isGroupCall,
		ts:        meta.Timestamp.Unix(),
		seen:      time.Now(),
	}
	c.callerName = b.callerName(chat, caller, c.chatName)
	if !b.calls.add(meta.CallID, c) {
		return
	}
	b.BroadcastEvent("call_incoming", CallIncomingEvent{
		ConversationJID:  c.chatJID,
		ConversationName: c.chatName.name,
		CallerJID:        c.senderJID,
		CallerName:       c.callerName,
		IsVideo:          c.isVideo,
		CallID:           meta.CallID,
		IsGroup:          c.isGroup,
	})
}

// callAccepted: the call was answered — on another of the user's devices for a
// 1:1 call. In a group call other members accepting means nothing for us.
func (b *Bridge) callAccepted(meta types.BasicCallMeta) {
	b.logCall("accept", meta, "")
	c := b.calls.get(meta.CallID)
	if c == nil || (c.isGroup && !b.isOwnUser(meta.From)) {
		return
	}
	if !b.calls.markAnswered(meta.CallID) {
		return
	}
	b.recordCall(meta.CallID, c, callAnswered)
	b.BroadcastEvent("call_ended", CallEndedEvent{
		ConversationJID: c.chatJID,
		CallID:          meta.CallID,
		Answered:        true,
	})
}

// callEnded handles terminate/reject. A call that ends unanswered is missed,
// except that the user declining it on their phone (a reject from one of
// their own devices) is recorded without counting as unread. In a group call,
// another member leaving or declining doesn't end it for us — only the caller
// or the user's own devices do.
func (b *Bridge) callEnded(meta types.BasicCallMeta, kind, reason string) {
	b.logCall(kind, meta, "reason="+reason)
	c := b.calls.get(meta.CallID)
	if c == nil {
		return // not a call we saw ring (placed by the user, or before startup)
	}
	fromOwn := b.isOwnUser(meta.From)
	if c.isGroup && !fromOwn && !b.sameUser(meta.From, meta.CallCreator) && !b.sameUser(meta.From, meta.CallCreatorAlt) {
		return
	}
	b.calls.end(meta.CallID)

	declined := kind == "reject" && fromOwn
	if c.answered || declined {
		if declined {
			b.recordCall(meta.CallID, c, callDeclined)
		}
		b.BroadcastEvent("call_ended", CallEndedEvent{
			ConversationJID: c.chatJID,
			CallID:          meta.CallID,
			Answered:        c.answered,
		})
		return
	}

	if b.recordCall(meta.CallID, c, callMissed) {
		b.IncrementUnread(c.chatJID)
	}
	b.BroadcastEvent("call_missed", CallMissedEvent{
		ConversationJID:  c.chatJID,
		ConversationName: c.chatName.name,
		CallerJID:        c.senderJID,
		CallerName:       c.callerName,
		IsVideo:          c.isVideo,
		IsGroup:          c.isGroup,
		CallID:           meta.CallID,
	})
}

// recordCall writes the call's row in its chat. Insert-or-ignore on the call
// ID makes redelivered call stanzas harmless; returns whether it was new.
func (b *Bridge) recordCall(callID string, c *pendingCall, outcome callOutcome) bool {
	status := "read"
	if outcome == callMissed {
		status = "sent" // counts as unread until the chat is read
	}
	inserted, err := b.InsertMessage(c.row(callID, outcome, status))
	if err != nil {
		b.log.Errorf("Failed to record call %s: %v", callID, err)
		return false
	}
	if inserted {
		// Creates the chat for a call from a new number (an ad-hoc group call
		// lives in the caller's DM).
		b.UpsertConversation(c.chatJID, c.chatName, callRowID(callID), c.ts)
	}
	return inserted
}

// historyCallRow turns a history-sync missed-call stub (no message content,
// only a stub type) into the same row a live missed call gets. ok is false
// for every other stub.
func historyCallRow(wm *waWeb.WebMessageInfo, chatJID, senderJID string) (row *MessageRow, ok bool) {
	c := &pendingCall{chatJID: chatJID, senderJID: senderJID, ts: int64(wm.GetMessageTimestamp())}
	switch wm.GetMessageStubType() {
	case waWeb.WebMessageInfo_CALL_MISSED_VOICE:
	case waWeb.WebMessageInfo_CALL_MISSED_VIDEO:
		c.isVideo = true
	case waWeb.WebMessageInfo_CALL_MISSED_GROUP_VOICE:
		c.isGroup = true
	case waWeb.WebMessageInfo_CALL_MISSED_GROUP_VIDEO:
		c.isGroup, c.isVideo = true, true
	default:
		return nil, false
	}
	return c.row(wm.GetKey().GetID(), callMissed, "read"), true
}
