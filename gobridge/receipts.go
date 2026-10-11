package main

import (
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

func (b *Bridge) handleReceipt(evt *events.Receipt) {
	// Resolve LID → PN so the JID matches the conversation row written by
	// handleMessage (which stores conversations under the phone-based JID).
	// Without this, ReadSelf receipts for LID chats fail to clear unread.
	chatJID := b.resolveLIDJID(evt.Chat).String()

	ids := make([]string, len(evt.MessageIDs))
	for i, id := range evt.MessageIDs {
		ids[i] = string(id)
	}
	b.applyReceipt(chatJID, ids, evt.Type, evt.IsFromMe, evt.Timestamp.Unix())
}

// receiptEffect classifies a receipt. ownRead means the user read incoming
// messages on another of their devices: "read-self" when read receipts are
// off, or a plain "read" sent from our own JID when they're on. Every other
// read/delivered receipt is a contact acknowledging one of OUR messages, so it
// only advances the outgoing status — it must never clear our unread count
// (that once made a chat look read the moment the other person read our
// reply). Anything else (played, sender, retry, …) has no effect.
func receiptEffect(t types.ReceiptType, isFromMe bool) (ownRead bool, outgoingStatus string) {
	switch {
	case t == types.ReceiptTypeReadSelf, t == types.ReceiptTypeRead && isFromMe:
		return true, ""
	case isFromMe:
		return false, ""
	case t == types.ReceiptTypeRead:
		return false, "read"
	case t == types.ReceiptTypeDelivered:
		return false, "delivered"
	}
	return false, ""
}

// applyReceipt applies a receipt for ids in chatJID, sent at ts. The user's
// own read marks the chat read through the newest of those messages — not
// the whole chat, which can hold newer ones they haven't seen. When none of
// them is stored (dropped, pruned, or not received yet), the receipt's time
// bounds the read instead.
func (b *Bridge) applyReceipt(chatJID string, ids []string, t types.ReceiptType, isFromMe bool, ts int64) {
	ownRead, status := receiptEffect(t, isFromMe)
	switch {
	case ownRead:
		jid, remaining, ok := b.MarkReadThrough(ids)
		if !ok {
			jid, remaining = chatJID, b.MarkChatReadUpTo(chatJID, ts)
		}
		if remaining > 0 {
			b.BroadcastEvent("conversation_updated", ConversationUpdatedEvent{JID: jid})
			return
		}
		b.BroadcastEvent("read_self", ReadSelfEvent{
			ConversationJID: jid,
			MessageIDs:      ids,
		})
	case status != "":
		b.AdvanceOutgoingStatus(ids, status)
		b.BroadcastEvent("receipt", ReceiptEvent{
			ConversationJID: chatJID,
			MessageIDs:      ids,
			Type:            status,
		})
	}
}

func (b *Bridge) handleChatPresence(evt *events.ChatPresence) {
	composing := evt.State == types.ChatPresenceComposing
	// Keyed like messages, so the indicator matches the open conversation
	// (and the sender's participant name) in @lid chats too.
	b.BroadcastEvent("typing", TypingEvent{
		JID:       b.resolveLIDJID(evt.Chat).String(),
		SenderJID: b.canonicalSenderJID(evt.Sender),
		Composing: composing,
	})
}
