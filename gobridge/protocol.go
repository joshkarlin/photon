package main

import "encoding/json"

// Envelope is the wrapper around every request, response and event exchanged
// with Kotlin (one per line; see transport.go).
type Envelope struct {
	ID      string          `json:"id,omitempty"`
	Type    string          `json:"type"`
	Payload json.RawMessage `json:"payload,omitempty"`
}

// Request payloads (Kotlin -> Go)

type SendMessagePayload struct {
	JID       string `json:"jid"`
	Text      string `json:"text"`
	ReplyToID string `json:"reply_to_id,omitempty"`
}

type SendMediaPayload struct {
	JID       string `json:"jid"`
	FilePath  string `json:"file_path"`
	MimeType  string `json:"mime_type"`
	Caption   string `json:"caption,omitempty"`
	ReplyToID string `json:"reply_to_id,omitempty"`
	// FileName is the name the user's file had (FilePath is a staged copy).
	// A document is sent under it; other media ignore it.
	FileName string `json:"file_name,omitempty"`
}

type SendReactionPayload struct {
	JID       string `json:"jid"`
	MessageID string `json:"message_id"`
	SenderJID string `json:"sender_jid"`
	Emoji     string `json:"emoji"`
}

type MarkReadPayload struct {
	JID        string   `json:"jid"`
	MessageIDs []string `json:"message_ids"`
}

type EditMessagePayload struct {
	JID       string `json:"jid"`
	MessageID string `json:"message_id"`
	NewText   string `json:"new_text"`
}

type PairingCodePayload struct {
	Phone string `json:"phone"`
}

type DownloadMediaPayload struct {
	MessageID string `json:"message_id"`
}

type RetryMessagePayload struct {
	MessageID string `json:"message_id"`
}

type DeleteMessagePayload struct {
	JID         string `json:"jid"`
	MessageID   string `json:"message_id"`
	ForEveryone bool   `json:"for_everyone"`
}

type ResolveParticipantsPayload struct {
	JID string `json:"jid"`
}

type SendTypingPayload struct {
	JID       string `json:"jid"`
	Composing bool   `json:"composing"`
}

type SetRetentionPayload struct {
	MaxMessages  int `json:"max_messages"`
	MaxDays      int `json:"max_days"`
	MediaTTLMins int `json:"media_ttl_mins"`
}

// Response/event payloads (Go -> Kotlin)

type SendMessageResponse struct {
	MessageID string `json:"message_id"`
	Timestamp int64  `json:"timestamp"`
}

type PairingCodeResponse struct {
	Code string `json:"code"`
}

type DownloadMediaResponse struct {
	LocalPath string `json:"local_path"`
}

type ConnectionStateEvent struct {
	State  string `json:"state"` // "connecting", "connected", "disconnected", "logged_out"
	Reason string `json:"reason,omitempty"`
}

type QrCodeEvent struct {
	Codes []string `json:"codes"`
}

type PairSuccessEvent struct {
	JID      string `json:"jid"`
	Platform string `json:"platform"`
}

type PairErrorEvent struct {
	Error string `json:"error"`
}

// NewMessageEvent: a message was stored. Names are the stored ones — the best
// WhatsApp gives (address book, verified business, push name) or "", never a
// number — for Kotlin to put the user's LP3 contacts above and fall back from
// to the number in SenderJID.
type NewMessageEvent struct {
	ConversationJID string `json:"conversation_jid"`
	MessageID       string `json:"message_id"`
	IsGroup         bool   `json:"is_group"`
	// ConversationName is a group's subject or a DM's name.
	ConversationName string `json:"conversation_name"`
	// SenderJID is the sender without device suffix, by phone number when the
	// LID mapping is known.
	SenderJID string `json:"sender_jid"`
	// SenderName is the sender's name; "" for the user's own messages.
	SenderName  string `json:"sender_name"`
	TextBody    string `json:"text_body,omitempty"`
	ContentType string `json:"content_type,omitempty"`
	IsFromMe    bool   `json:"is_from_me"`
}

type MessageUpdatedEvent struct {
	ConversationJID string `json:"conversation_jid"`
	MessageID       string `json:"message_id"`
}

// MessageRevokedEvent: a message was deleted for everyone and now renders as
// "[ deleted ]". When WasUnread and UnreadCount is 0, the notification that
// announced it should go.
type MessageRevokedEvent struct {
	ConversationJID string `json:"conversation_jid"`
	MessageID       string `json:"message_id"`
	WasUnread       bool   `json:"was_unread"`
	UnreadCount     int    `json:"unread_count"`
}

// ReceiptEvent reports a contact's delivered/read receipt for our outgoing
// messages. It never means the user read anything — see ReadSelfEvent.
type ReceiptEvent struct {
	ConversationJID string   `json:"conversation_jid"`
	MessageIDs      []string `json:"message_ids"`
	Type            string   `json:"type"` // "delivered", "read"
}

// ReadSelfEvent means the user read the conversation on another of their
// devices (or marked it read there): unread is now 0, so its notification can
// go. MessageIDs is empty when a whole chat was marked read.
type ReadSelfEvent struct {
	ConversationJID string   `json:"conversation_jid"`
	MessageIDs      []string `json:"message_ids,omitempty"`
}

type TypingEvent struct {
	JID       string `json:"jid"`
	SenderJID string `json:"sender_jid"`
	Composing bool   `json:"composing"`
}

type ConversationUpdatedEvent struct {
	JID string `json:"jid"`
}

// NamesChangedEvent: stored names changed outside a message — an address-book
// edit, a new push or verified business name, a group rename, the reconcile.
// JIDs are the conversations to refresh: a DM renamed, or a group whose own
// name or one of whose members' names changed.
type NamesChangedEvent struct {
	JIDs []string `json:"jids"`
}

type HistorySyncProgressEvent struct {
	ConversationsDone  int `json:"conversations_done"`
	ConversationsTotal int `json:"conversations_total"`
	MessagesTotal      int `json:"messages_total"`
}

// CallIncomingEvent: a call is ringing on the user's other devices. Names
// follow NewMessageEvent's rules: the caller's (CallerJID's) own name, and the
// chat's — a group's subject for a call placed in a group.
type CallIncomingEvent struct {
	ConversationJID  string `json:"conversation_jid"`
	ConversationName string `json:"conversation_name"`
	CallerJID        string `json:"caller_jid"`
	CallerName       string `json:"caller_name"`
	IsVideo          bool   `json:"is_video"`
	CallID           string `json:"call_id"`
	IsGroup          bool   `json:"is_group"`
}

// CallEndedEvent: a ringing call stopped without being missed — answered
// elsewhere, or declined by the user on another device.
type CallEndedEvent struct {
	ConversationJID string `json:"conversation_jid"`
	CallID          string `json:"call_id"`
	Answered        bool   `json:"answered"`
}

// CallMissedEvent: a call ended unanswered; a "Missed … call" row was added
// to the chat and counted as unread. Fields as in CallIncomingEvent.
type CallMissedEvent struct {
	ConversationJID  string `json:"conversation_jid"`
	ConversationName string `json:"conversation_name"`
	CallerJID        string `json:"caller_jid"`
	CallerName       string `json:"caller_name"`
	IsVideo          bool   `json:"is_video"`
	IsGroup          bool   `json:"is_group"`
	CallID           string `json:"call_id"`
}

type ErrorResponse struct {
	Error string `json:"error"`
}
