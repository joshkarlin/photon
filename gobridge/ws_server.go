package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"

	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"nhooyr.io/websocket"
)

// RegisterWSHandler registers the WebSocket handler on the given mux.
func (b *Bridge) RegisterWSHandler(mux *http.ServeMux) {
	mux.HandleFunc("/ws", func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
			InsecureSkipVerify: true, // localhost only
		})
		if err != nil {
			b.log.Errorf("WS accept error: %v", err)
			return
		}

		b.AddConn(conn)
		b.log.Infof("WebSocket client connected")

		// Send current connection state
		state := "disconnected"
		if b.client.IsConnected() {
			state = "connected"
		}
		if b.client.Store.ID == nil {
			state = "logged_out"
		}
		b.sendToConn(conn, WsMessage{
			Type:    "connection_state",
			Payload: mustMarshal(ConnectionStateEvent{State: state}),
		})

		defer func() {
			b.RemoveConn(conn)
			conn.Close(websocket.StatusNormalClosure, "")
			b.log.Infof("WebSocket client disconnected")
		}()

		// Read loop
		for {
			_, data, err := conn.Read(r.Context())
			if err != nil {
				break
			}

			var msg WsMessage
			if err := json.Unmarshal(data, &msg); err != nil {
				b.log.Warnf("Invalid WS message: %v", err)
				continue
			}

			response := b.handleRequest(r.Context(), msg)
			b.sendToConn(conn, response)
		}
	})
}

func (b *Bridge) sendToConn(conn *websocket.Conn, msg WsMessage) {
	data, err := json.Marshal(msg)
	if err != nil {
		b.log.Errorf("Failed to marshal response: %v", err)
		return
	}
	conn.Write(context.Background(), websocket.MessageText, data)
}

func (b *Bridge) handleRequest(ctx context.Context, msg WsMessage) WsMessage {
	var payload json.RawMessage
	var err error

	switch msg.Type {
	case "send_message":
		payload, err = b.handleSendMessage(ctx, msg.Payload)
	case "retry_message":
		payload, err = b.handleRetryMessage(ctx, msg.Payload)
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
	default:
		err = fmt.Errorf("unknown request type: %s", msg.Type)
	}

	resp := WsMessage{
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
	if err := b.RetryTextMessage(ctx, p.MessageID); err != nil {
		return nil, err
	}
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

	targetJID, err := types.ParseJID(p.JID)
	if err != nil {
		return nil, err
	}

	senderJID, err := types.ParseJID(p.SenderJID)
	if err != nil {
		return nil, err
	}

	reactionMsg := b.client.BuildReaction(targetJID, senderJID, p.MessageID, p.Emoji)
	_, err = b.client.SendMessage(ctx, targetJID, reactionMsg)
	if err != nil {
		return nil, err
	}

	b.UpsertReaction(p.MessageID, p.SenderJID, p.Emoji, 0)
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
	err := b.MarkRead(ctx, p.JID, p.MessageIDs)
	if err == nil {
		b.ResetUnread(p.JID)
	}
	return mustMarshal(struct{}{}), err
}

func (b *Bridge) handleEditMessage(ctx context.Context, raw json.RawMessage) (json.RawMessage, error) {
	var p EditMessagePayload
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, err
	}

	targetJID, err := types.ParseJID(p.JID)
	if err != nil {
		return nil, err
	}

	editMsg := b.client.BuildEdit(targetJID, p.MessageID, &waE2E.Message{
		Conversation: &p.NewText,
	})
	_, err = b.client.SendMessage(ctx, targetJID, editMsg)
	if err != nil {
		return nil, err
	}

	b.UpdateMessageEdit(p.MessageID, p.NewText, 1)
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

func parseJID(jid string) (types.JID, error) {
	return types.ParseJID(jid)
}
