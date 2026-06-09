package main

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"go.mau.fi/whatsmeow"
	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"google.golang.org/protobuf/proto"
)

// DownloadMedia downloads media for a message and saves it to disk.
func (b *Bridge) DownloadMedia(msgID string, msg *waE2E.Message) (string, error) {
	ctx := context.Background()
	var data []byte
	var err error
	ext := ".bin"

	switch {
	case msg.GetImageMessage() != nil:
		m := msg.GetImageMessage()
		data, err = b.client.Download(ctx, m)
		ext = mimeToExt(m.GetMimetype())
	case msg.GetVideoMessage() != nil:
		m := msg.GetVideoMessage()
		data, err = b.client.Download(ctx, m)
		ext = mimeToExt(m.GetMimetype())
	case msg.GetAudioMessage() != nil:
		m := msg.GetAudioMessage()
		data, err = b.client.Download(ctx, m)
		ext = mimeToExt(m.GetMimetype())
	case msg.GetDocumentMessage() != nil:
		m := msg.GetDocumentMessage()
		data, err = b.client.Download(ctx, m)
		ext = mimeToExt(m.GetMimetype())
	case msg.GetStickerMessage() != nil:
		m := msg.GetStickerMessage()
		data, err = b.client.Download(ctx, m)
		ext = mimeToExt(m.GetMimetype())
	default:
		return "", fmt.Errorf("no downloadable media in message %s", msgID)
	}

	if err != nil {
		return "", fmt.Errorf("download failed for %s: %w", msgID, err)
	}

	filename := msgID + ext
	path := filepath.Join(b.dataDir, "media", filename)
	if err := os.WriteFile(path, data, 0644); err != nil {
		return "", fmt.Errorf("write failed for %s: %w", msgID, err)
	}

	b.UpdateMediaURL(msgID, path)
	return path, nil
}

// DownloadMediaByID looks up a message's raw proto and downloads its media.
func (b *Bridge) DownloadMediaByID(msgID string) (string, error) {
	raw, err := b.GetMessageRawProto(msgID)
	if err != nil {
		return "", err
	}

	msg := &waE2E.Message{}
	if err := proto.Unmarshal(raw, msg); err != nil {
		return "", fmt.Errorf("unmarshal failed for %s: %w", msgID, err)
	}

	return b.DownloadMedia(msgID, msg)
}

// saveThumbnail saves inline JPEG thumbnail bytes to disk.
func (b *Bridge) saveThumbnail(msgID string, data []byte) string {
	path := filepath.Join(b.dataDir, "thumbs", msgID+".jpg")
	if err := os.WriteFile(path, data, 0644); err != nil {
		b.log.Warnf("Failed to save thumbnail for %s: %v", msgID, err)
		return ""
	}
	return path
}

// UploadAndSend uploads a file and sends it as a media message.
//
// Mirrors SendTextMessage's pre-insert pattern so a failed upload or send
// leaves a "failed" row in the chat instead of vanishing silently. The
// row goes in with status="sending" before the upload starts so users see
// it the instant they tap send.
func (b *Bridge) UploadAndSend(jid, filePath, mimeType, caption, replyToID string) (string, int64, error) {
	targetJID, err := parseJID(jid)
	if err != nil {
		return "", 0, err
	}
	// Resolve @lid targets so the conversation row keys by phone JID and
	// doesn't duplicate a row handleMessage stored under the resolved JID.
	targetJID = b.resolveLIDJID(targetJID)
	jid = targetJID.String()

	id := b.client.GenerateMessageID()
	ts := time.Now().Unix()

	contentType := "document"
	switch {
	case strings.HasPrefix(mimeType, "image/"):
		contentType = "image"
	case strings.HasPrefix(mimeType, "video/"):
		contentType = "video"
	case strings.HasPrefix(mimeType, "audio/"):
		contentType = "audio"
	}

	b.InsertMessage(&MessageRow{
		ID:              id,
		ConversationJID: jid,
		SenderJID:       b.client.Store.ID.String(),
		Timestamp:       ts,
		ContentType:     contentType,
		TextBody:        sql.NullString{String: caption, Valid: caption != ""},
		MediaMime:       sql.NullString{String: mimeType, Valid: true},
		MediaURL:        sql.NullString{String: filePath, Valid: true},
		ReplyToID:       sql.NullString{String: replyToID, Valid: replyToID != ""},
		IsFromMe:        true,
		Status:          "sending",
	})
	b.UpsertConversation(jid, "", false, id, ts)
	b.BroadcastEvent("new_message", NewMessageEvent{
		ConversationJID: jid, MessageID: id, TextBody: caption,
		ContentType: contentType, IsFromMe: true,
	})

	markFailed := func() {
		b.UpdateMessageStatus(id, "failed")
		b.BroadcastEvent("message_updated", MessageUpdatedEvent{
			ConversationJID: jid, MessageID: id,
		})
	}

	data, err := os.ReadFile(filePath)
	if err != nil {
		markFailed()
		return id, ts, fmt.Errorf("read file failed: %w", err)
	}

	mediaType := whatsmeow.MediaDocument
	switch {
	case strings.HasPrefix(mimeType, "image/"):
		mediaType = whatsmeow.MediaImage
	case strings.HasPrefix(mimeType, "video/"):
		mediaType = whatsmeow.MediaVideo
	case strings.HasPrefix(mimeType, "audio/"):
		mediaType = whatsmeow.MediaAudio
	}

	ctx := context.Background()
	resp, err := b.client.Upload(ctx, data, mediaType)
	if err != nil {
		markFailed()
		return id, ts, fmt.Errorf("upload failed: %w", err)
	}

	msg := buildMediaMessage(resp, mimeType, caption, replyToID, data)
	sendResp, err := b.client.SendMessage(ctx, targetJID, msg, whatsmeow.SendRequestExtra{ID: id})
	if err != nil {
		markFailed()
		return id, ts, fmt.Errorf("send failed: %w", err)
	}

	b.UpdateMessageStatusAndTimestamp(id, "sent", sendResp.Timestamp.Unix())
	b.UpsertConversation(jid, "", false, id, sendResp.Timestamp.Unix())
	b.BroadcastEvent("message_updated", MessageUpdatedEvent{
		ConversationJID: jid, MessageID: id,
	})

	return id, sendResp.Timestamp.Unix(), nil
}

func buildMediaMessage(resp whatsmeow.UploadResponse, mimeType, caption, replyToID string, data []byte) *waE2E.Message {
	var ctx *waE2E.ContextInfo
	if replyToID != "" {
		ctx = &waE2E.ContextInfo{StanzaID: &replyToID}
	}

	switch {
	case strings.HasPrefix(mimeType, "image/"):
		return &waE2E.Message{
			ImageMessage: &waE2E.ImageMessage{
				URL:           &resp.URL,
				DirectPath:    &resp.DirectPath,
				MediaKey:      resp.MediaKey,
				FileEncSHA256: resp.FileEncSHA256,
				FileSHA256:    resp.FileSHA256,
				FileLength:    ptrUint64(uint64(len(data))),
				Mimetype:      &mimeType,
				Caption:       nilIfEmpty(caption),
				ContextInfo:   ctx,
			},
		}
	case strings.HasPrefix(mimeType, "video/"):
		return &waE2E.Message{
			VideoMessage: &waE2E.VideoMessage{
				URL:           &resp.URL,
				DirectPath:    &resp.DirectPath,
				MediaKey:      resp.MediaKey,
				FileEncSHA256: resp.FileEncSHA256,
				FileSHA256:    resp.FileSHA256,
				FileLength:    ptrUint64(uint64(len(data))),
				Mimetype:      &mimeType,
				Caption:       nilIfEmpty(caption),
				ContextInfo:   ctx,
			},
		}
	case strings.HasPrefix(mimeType, "audio/"):
		ptt := true
		oggMime := "audio/ogg; codecs=opus"
		audioMime := &mimeType
		if strings.Contains(mimeType, "ogg") {
			audioMime = &oggMime
		}
		return &waE2E.Message{
			AudioMessage: &waE2E.AudioMessage{
				URL:           &resp.URL,
				DirectPath:    &resp.DirectPath,
				MediaKey:      resp.MediaKey,
				FileEncSHA256: resp.FileEncSHA256,
				FileSHA256:    resp.FileSHA256,
				FileLength:    ptrUint64(uint64(len(data))),
				Mimetype:      audioMime,
				PTT:           &ptt,
				ContextInfo:   ctx,
			},
		}
	default:
		return &waE2E.Message{
			DocumentMessage: &waE2E.DocumentMessage{
				URL:           &resp.URL,
				DirectPath:    &resp.DirectPath,
				MediaKey:      resp.MediaKey,
				FileEncSHA256: resp.FileEncSHA256,
				FileSHA256:    resp.FileSHA256,
				FileLength:    ptrUint64(uint64(len(data))),
				Mimetype:      &mimeType,
				Caption:       nilIfEmpty(caption),
				ContextInfo:   ctx,
			},
		}
	}
}

func mimeToExt(mime string) string {
	switch mime {
	case "image/jpeg":
		return ".jpg"
	case "image/png":
		return ".png"
	case "image/webp":
		return ".webp"
	case "video/mp4":
		return ".mp4"
	case "audio/ogg; codecs=opus", "audio/ogg":
		return ".ogg"
	case "audio/mpeg":
		return ".mp3"
	case "application/pdf":
		return ".pdf"
	default:
		if strings.HasPrefix(mime, "image/") {
			return ".jpg"
		}
		if strings.HasPrefix(mime, "video/") {
			return ".mp4"
		}
		if strings.HasPrefix(mime, "audio/") {
			return ".ogg"
		}
		return ".bin"
	}
}

func ptrUint64(v uint64) *uint64 { return &v }
func nilIfEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}
