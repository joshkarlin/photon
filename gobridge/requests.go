package main

import (
	"context"
	"encoding/json"
	"fmt"

	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
)

// handleRequest runs one request from Kotlin and builds its response.
func (b *Bridge) handleRequest(ctx context.Context, msg Envelope) Envelope {
	var payload json.RawMessage
	var err error

	switch msg.Type {
	case "send_message":
		payload, err = b.handleSendMessage(ctx, msg.Payload)
	case "retry_message":
		payload, err = b.handleRetryMessage(ctx, msg.Payload)
	case "delete_message":
		payload, err = b.handleDeleteMessage(ctx, msg.Payload)
	case "send_media":
		payload, err = b.handleSendMedia(ctx, msg.Payload)
	case "send_reaction":
		payload, err = b.handleSendReaction(ctx, msg.Payload)
	case "send_typing":
		payload, err = b.handleSendTyping(ctx, msg.Payload)
	case "mark_read":
		payload, err = b.handleMarkRead(ctx, msg.Payload)
	case "edit_message":
		payload, err = b.handleEditMessage(ctx, msg.Payload)
	case "download_media":
		payload, err = b.handleDownloadMedia(ctx, msg.Payload)
	case "get_pairing_code":
		payload, err = b.handleGetPairingCode(ctx, msg.Payload)
	case "get_qr":
		b.StartQRPairing(ctx)
		payload = mustMarshal(struct{}{})
	case "logout":
		err = b.Logout()
		payload = mustMarshal(struct{}{})
	case "set_retention":
		payload, err = b.handleSetRetention(ctx, msg.Payload)
	case "resolve_participants":
		payload, err = b.handleResolveParticipants(msg.Payload)
	case "reconnect":
		payload, err = b.handleReconnect()
	case "check_on_whatsapp":
		payload, err = b.handleCheckOnWhatsApp(ctx, msg.Payload)
	default:
		err = fmt.Errorf("unknown request type: %s", msg.Type)
	}

	resp := Envelope{
		ID:   msg.ID,
		Type: msg.Type + "_response",
	}

	if err != nil {
		resp.Payload = mustMarshal(ErrorResponse{Error: err.Error()})
	} else {
		resp.Payload = payload
	}

	return resp
}

func (b *Bridge) handleSendMessage(ctx context.Context, raw json.RawMessage) (json.RawMessage, error) {
	var p SendMessagePayload
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, err
	}
	id, ts, err := b.SendTextMessage(ctx, p.JID, p.Text, p.ReplyToID)
	if err != nil {
		return nil, err
	}
	return mustMarshal(SendMessageResponse{MessageID: id, Timestamp: ts}), nil
}

func (b *Bridge) handleRetryMessage(ctx context.Context, raw json.RawMessage) (json.RawMessage, error) {
	var p RetryMessagePayload
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, err
	}
	if err := b.RetryMessage(ctx, p.MessageID); err != nil {
		return nil, err
	}
	return mustMarshal(struct{}{}), nil
}

func (b *Bridge) handleDeleteMessage(ctx context.Context, raw json.RawMessage) (json.RawMessage, error) {
	var p DeleteMessagePayload
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, err
	}
	if err := b.DeleteMessage(ctx, p.JID, p.MessageID, p.ForEveryone); err != nil {
		return nil, err
	}
	return mustMarshal(struct{}{}), nil
}

func (b *Bridge) handleResolveParticipants(raw json.RawMessage) (json.RawMessage, error) {
	var p ResolveParticipantsPayload
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, err
	}
	b.ResolveGroupParticipants(p.JID)
	return mustMarshal(struct{}{}), nil
}

func (b *Bridge) handleSendMedia(ctx context.Context, raw json.RawMessage) (json.RawMessage, error) {
	var p SendMediaPayload
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, err
	}
	id, ts, err := b.UploadAndSend(p.JID, p.FilePath, p.MimeType, p.Caption, p.ReplyToID)
	if err != nil {
		return nil, err
	}
	return mustMarshal(SendMessageResponse{MessageID: id, Timestamp: ts}), nil
}

func (b *Bridge) handleSendReaction(ctx context.Context, raw json.RawMessage) (json.RawMessage, error) {
	var p SendReactionPayload
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, err
	}

	parsedJID, err := types.ParseJID(p.JID)
	if err != nil {
		return nil, err
	}
	parsedSender, err := types.ParseJID(p.SenderJID)
	if err != nil {
		return nil, err
	}
	// Resolve LID → phone so the reaction row keys on the same sender_jid the
	// live handler stores (handleMessage), otherwise a group reaction lands
	// under a different key and never folds with the canonical row.
	targetJID := b.resolveLIDJID(parsedJID)
	senderJID := b.resolveLIDJID(parsedSender)

	reactionMsg := b.client.BuildReaction(targetJID, senderJID, p.MessageID, p.Emoji)
	_, err = b.client.SendMessage(ctx, targetJID, reactionMsg)
	if err != nil {
		return nil, err
	}

	b.UpsertReaction(p.MessageID, senderJID.String(), p.Emoji, 0)
	return mustMarshal(struct{}{}), nil
}

func (b *Bridge) handleSendTyping(ctx context.Context, raw json.RawMessage) (json.RawMessage, error) {
	var p SendTypingPayload
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, err
	}
	err := b.SendTyping(ctx, p.JID, p.Composing)
	return mustMarshal(struct{}{}), err
}

func (b *Bridge) handleMarkRead(ctx context.Context, raw json.RawMessage) (json.RawMessage, error) {
	var p MarkReadPayload
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, err
	}
	// Clear the local unread badge whether or not the network read receipt
	// (blue ticks to the sender) goes through: coupling the two meant any
	// MarkRead failure (offline, LID-chat quirks) left the badge stuck. The
	// listed ids are the newest incoming rows the UI showed, newest first;
	// everything up to the newest of them is read, older rows included (a
	// later revoke must not think one was still unread), and anything that
	// arrived after the UI loaded its list stays unread. The rows' own chat is
	// marked, so it works before an @lid chat is merged into its phone JID.
	b.MarkReadThrough(p.MessageIDs)
	// The receipt goes to the LID-resolved chat, like every other send.
	jid := b.resolveLIDString(p.JID)
	ids := b.WithoutCallRows(p.MessageIDs)
	if len(ids) == 0 {
		return mustMarshal(struct{}{}), nil
	}
	err := b.MarkRead(ctx, jid, ids)
	return mustMarshal(struct{}{}), err
}

func (b *Bridge) handleEditMessage(ctx context.Context, raw json.RawMessage) (json.RawMessage, error) {
	var p EditMessagePayload
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, err
	}

	parsedJID, err := types.ParseJID(p.JID)
	if err != nil {
		return nil, err
	}
	// Resolve LID → phone to match every other send path; the conversation row
	// is keyed by the resolved JID.
	targetJID := b.resolveLIDJID(parsedJID)

	editMsg := b.client.BuildEdit(targetJID, p.MessageID, &waE2E.Message{
		Conversation: &p.NewText,
	})
	_, err = b.client.SendMessage(ctx, targetJID, editMsg)
	if err != nil {
		return nil, err
	}

	b.UpdateMessageEdit(p.MessageID, targetJID.String(), p.NewText, &author{isMe: true})
	// Tell connected UIs to refresh, mirroring the live edit path which
	// broadcasts message_updated (request responses don't flow through the
	// event stream).
	b.BroadcastEvent("message_updated", MessageUpdatedEvent{
		ConversationJID: targetJID.String(),
		MessageID:       p.MessageID,
	})
	return mustMarshal(struct{}{}), nil
}

func (b *Bridge) handleDownloadMedia(ctx context.Context, raw json.RawMessage) (json.RawMessage, error) {
	var p DownloadMediaPayload
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, err
	}
	path, err := b.DownloadMediaByID(p.MessageID)
	if err != nil {
		return nil, err
	}
	return mustMarshal(DownloadMediaResponse{LocalPath: path}), nil
}

func (b *Bridge) handleGetPairingCode(ctx context.Context, raw json.RawMessage) (json.RawMessage, error) {
	var p PairingCodePayload
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, err
	}
	code, err := b.RequestPairingCode(ctx, p.Phone)
	if err != nil {
		return nil, err
	}
	return mustMarshal(PairingCodeResponse{Code: code}), nil
}

func (b *Bridge) handleSetRetention(ctx context.Context, raw json.RawMessage) (json.RawMessage, error) {
	var p SetRetentionPayload
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, err
	}
	b.SetRetention(p.MaxMessages, p.MaxDays, p.MediaTTLMins)
	// Run an immediate prune with new settings
	go func() {
		b.pruneMessages()
		b.pruneMedia()
	}()
	return mustMarshal(struct{}{}), nil
}

// Helper

func mustMarshal(v interface{}) json.RawMessage {
	data, _ := json.Marshal(v)
	return data
}
