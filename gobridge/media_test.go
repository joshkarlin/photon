package main

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"go.mau.fi/whatsmeow"
	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
)

// fakeWhatsApp stands in for WhatsApp's media upload and message send,
// recording what was sent. While offline, sends fail.
type fakeWhatsApp struct {
	offline bool
	sent    []*waE2E.Message
	ids     []string
}

func (f *fakeWhatsApp) attach(b *Bridge) {
	b.mediaUploader = func(context.Context, []byte, whatsmeow.MediaType) (whatsmeow.UploadResponse, error) {
		return whatsmeow.UploadResponse{URL: "https://mmg.example/x", DirectPath: "/x"}, nil
	}
	b.messageSender = func(_ context.Context, _ types.JID, msg *waE2E.Message, extra ...whatsmeow.SendRequestExtra) (whatsmeow.SendResponse, error) {
		if f.offline {
			return whatsmeow.SendResponse{}, errors.New("offline")
		}
		f.sent = append(f.sent, msg)
		f.ids = append(f.ids, extra[0].ID)
		return whatsmeow.SendResponse{Timestamp: time.Unix(1_900_000_000, 0)}, nil
	}
}

// mediaBridge is a bridge whose media sends reach fake, with a file staged
// as Kotlin stages one.
func mediaBridge(t *testing.T, staged string) (*Bridge, *fakeWhatsApp, string) {
	t.Helper()
	b := newFKClientBridge(t)
	b.dataDir = t.TempDir()
	path := filepath.Join(b.dataDir, staged)
	if err := os.WriteFile(path, []byte("%PDF-1.7"), 0o600); err != nil {
		t.Fatal(err)
	}
	fake := &fakeWhatsApp{}
	fake.attach(b)
	return b, fake, path
}

// sendMedia sends payload as Kotlin does, returning the stored message id.
func sendMedia(t *testing.T, b *Bridge, payload SendMediaPayload) (string, Envelope) {
	t.Helper()
	before := rowCount(t, b, `SELECT COUNT(*) FROM messages`)
	resp := b.handleRequest(context.Background(), Envelope{ID: "r1", Type: "send_media", Payload: mustMarshal(payload)})
	if rowCount(t, b, `SELECT COUNT(*) FROM messages`) != before+1 {
		t.Fatalf("send_media stored no row: %s", resp.Payload)
	}
	var id string
	if err := b.msgDB.QueryRow(`SELECT id FROM messages ORDER BY rowid DESC LIMIT 1`).Scan(&id); err != nil {
		t.Fatal(err)
	}
	return id, resp
}

func TestBuildMediaMessage_namesDocumentsOnly(t *testing.T) {
	withName := mediaText{FileName: "Q1 report.pdf"}

	doc := buildMediaMessage(whatsmeow.UploadResponse{}, "application/pdf", withName, "", []byte("x")).GetDocumentMessage()
	if doc.GetFileName() != "Q1 report.pdf" || doc.GetTitle() != "Q1 report.pdf" {
		t.Errorf("document file name %q, title %q", doc.GetFileName(), doc.GetTitle())
	}
	if doc.Caption != nil {
		t.Errorf("document caption = %q, want none", doc.GetCaption())
	}

	unnamed := buildMediaMessage(whatsmeow.UploadResponse{}, "application/pdf", mediaText{}, "", nil).GetDocumentMessage()
	if unnamed.FileName != nil || unnamed.Title != nil {
		t.Errorf("a document without a name got %q / %q", unnamed.GetFileName(), unnamed.GetTitle())
	}

	for _, mime := range []string{"image/jpeg", "video/mp4", "audio/ogg"} {
		msg := buildMediaMessage(whatsmeow.UploadResponse{}, mime, withName, "", nil)
		if msg.GetDocumentMessage() != nil {
			t.Errorf("%s was sent as a document", mime)
		}
		if got := msg.GetImageMessage().GetCaption() + msg.GetVideoMessage().GetCaption(); got != "" {
			t.Errorf("%s carries the file name as its caption %q", mime, got)
		}
	}
}

func TestMediaText_documentsKeepTheirNameAndOthersTheirCaption(t *testing.T) {
	cases := []struct {
		contentType string
		text        mediaText
		body        string
	}{
		{"document", mediaText{FileName: "Q1 report.pdf"}, "Q1 report.pdf"},
		{"image", mediaText{Caption: "look", FileName: "IMG_1.jpg"}, "look"},
		{"video", mediaText{FileName: "clip.mp4"}, ""},
	}
	for _, c := range cases {
		body := c.text.textBody(c.contentType)
		if body != c.body {
			t.Errorf("%s stores %q, want %q", c.contentType, body, c.body)
		}
		back := mediaTextOf(c.contentType, body)
		if c.contentType == "document" && back.FileName != c.text.FileName {
			t.Errorf("a retried document is named %q, want %q", back.FileName, c.text.FileName)
		}
		if c.contentType != "document" && (back.Caption != c.text.Caption || back.FileName != "") {
			t.Errorf("a retried %s reads back as %+v", c.contentType, back)
		}
	}
}

// A picked PDF goes out under its own name, not the staged copy's, and the
// thread shows that name.
func TestSendMedia_aDocumentGoesOutUnderItsName(t *testing.T) {
	b, fake, path := mediaBridge(t, "send_media_1700000000.pdf")

	id, resp := sendMedia(t, b, SendMediaPayload{
		JID: "447700900123@s.whatsapp.net", FilePath: path, MimeType: "application/pdf", FileName: "Q1 report.pdf",
	})

	var r SendMessageResponse
	if err := json.Unmarshal(resp.Payload, &r); err != nil || r.MessageID != id {
		t.Fatalf("response %s for %s", resp.Payload, id)
	}
	doc := fake.sent[0].GetDocumentMessage()
	if doc.GetFileName() != "Q1 report.pdf" {
		t.Errorf("sent as %q", doc.GetFileName())
	}
	if got := textBody(t, b, id); got != "Q1 report.pdf" {
		t.Errorf("stored text = %q, want the file name", got)
	}
	if _, status := storedIn(t, b, id); status != "sent" {
		t.Errorf("status = %s", status)
	}
}

func TestSendMedia_aPhotoIgnoresItsName(t *testing.T) {
	b, fake, path := mediaBridge(t, "send_media_1700000000.jpg")

	id, _ := sendMedia(t, b, SendMediaPayload{
		JID: "447700900123@s.whatsapp.net", FilePath: path, MimeType: "image/jpeg", FileName: "IMG_0001.jpg",
	})

	if fake.sent[0].GetImageMessage() == nil {
		t.Fatalf("sent %v", fake.sent[0])
	}
	if got := textBody(t, b, id); got != "" {
		t.Errorf("stored text = %q, want none", got)
	}
}

// A document that failed is retried under the same name and message id.
func TestRetryMessage_aDocumentKeepsItsName(t *testing.T) {
	b, fake, path := mediaBridge(t, "send_media_1700000000.pdf")
	fake.offline = true
	id, resp := sendMedia(t, b, SendMediaPayload{
		JID: "447700900123@s.whatsapp.net", FilePath: path, MimeType: "application/pdf", FileName: "Q1 report.pdf",
	})
	if _, status := storedIn(t, b, id); status != "failed" {
		t.Fatalf("offline send stored as %s (%s)", status, resp.Payload)
	}

	fake.offline = false
	if err := b.RetryMessage(context.Background(), id); err != nil {
		t.Fatal(err)
	}

	if got := fake.sent[0].GetDocumentMessage().GetFileName(); got != "Q1 report.pdf" {
		t.Errorf("retried as %q", got)
	}
	if fake.ids[0] != id {
		t.Errorf("retried as message %s, want %s", fake.ids[0], id)
	}
	if _, status := storedIn(t, b, id); status != "sent" {
		t.Errorf("status = %s", status)
	}
}
