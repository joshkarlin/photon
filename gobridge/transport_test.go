package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestLineWriter_concurrentSendsStayWholeLines(t *testing.T) {
	var buf bytes.Buffer
	lw := &lineWriter{w: &buf}
	big := strings.Repeat("x", 200_000) // a QR/history-sized payload
	var wg sync.WaitGroup
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			lw.send(Envelope{Type: "new_message", Payload: mustMarshal(map[string]string{
				"text_body": "line one\nline two", // embedded newline must not split the line
				"pad":       big,
			})})
		}()
	}
	wg.Wait()

	sc := bufio.NewScanner(&buf)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	lines := 0
	for sc.Scan() {
		var msg Envelope
		if err := json.Unmarshal(sc.Bytes(), &msg); err != nil || msg.Type != "new_message" {
			t.Fatalf("line %d is not a whole envelope: %v", lines, err)
		}
		lines++
	}
	if lines != 50 {
		t.Errorf("lines = %d, want 50", lines)
	}
}

func TestReadLines(t *testing.T) {
	long := strings.Repeat("y", 1<<20) // far beyond bufio.Scanner's 64KB default
	input := `{"id":"1","type":"get_qr"}` + "\n" +
		"\n" + // blank lines are ignored
		"not json\n" +
		`{"id":"2","type":"send_message","payload":{"text":"` + long + `"}}` + "\n" +
		`{"id":"3","type":"logout"}` // final line without a newline

	var got []Envelope
	var bad int
	err := readLines(strings.NewReader(input), func(m Envelope) { got = append(got, m) }, func(error) { bad++ })
	if err != nil {
		t.Fatalf("readLines: %v", err)
	}
	if len(got) != 3 || got[0].ID != "1" || got[1].ID != "2" || got[2].ID != "3" {
		t.Fatalf("envelopes = %+v", got)
	}
	if !strings.Contains(string(got[1].Payload), long) {
		t.Error("the 1MB line was truncated")
	}
	if bad != 1 {
		t.Errorf("malformed lines reported = %d, want 1", bad)
	}
}

func TestReadLines_returnsReadError(t *testing.T) {
	boom := errors.New("boom")
	err := readLines(io.MultiReader(strings.NewReader(`{"type":"x"}`+"\n"), errReader{boom}), func(Envelope) {}, func(error) {})
	if !errors.Is(err, boom) {
		t.Errorf("err = %v, want boom", err)
	}
}

type errReader struct{ err error }

func (r errReader) Read([]byte) (int, error) { return 0, r.err }

// The bridge answers requests on stdout with the request's ID and stops
// serving when stdin closes (the app died or is restarting it).
func TestServe_answersUntilStdinCloses(t *testing.T) {
	b := newClientBridge(t)
	outR, outW := io.Pipe()
	b.out = &lineWriter{w: outW}
	b.emit = b.writeEvent
	inR, inW := io.Pipe()

	served := make(chan error, 1)
	go func() { served <- b.Serve(context.Background(), inR) }()

	out := bufio.NewReader(outR)
	readMsg := func() Envelope {
		t.Helper()
		line, err := out.ReadBytes('\n')
		if err != nil {
			t.Fatalf("read: %v", err)
		}
		var m Envelope
		if err := json.Unmarshal(line, &m); err != nil {
			t.Fatalf("bad line %q: %v", line, err)
		}
		return m
	}

	if first := readMsg(); first.Type != "connection_state" {
		t.Errorf("first line = %s, want the current connection_state", first.Type)
	}

	go inW.Write([]byte(`{"id":"r1","type":"no_such_request"}` + "\n"))
	resp := readMsg()
	if resp.ID != "r1" || resp.Type != "no_such_request_response" || !strings.Contains(string(resp.Payload), "unknown request type") {
		t.Errorf("response = %s %s %s", resp.ID, resp.Type, resp.Payload)
	}

	inW.Close()
	select {
	case err := <-served:
		if err != nil {
			t.Errorf("Serve = %v, want nil at EOF", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Serve kept running after stdin closed")
	}
}

func TestSendableFile(t *testing.T) {
	dataDir := t.TempDir()
	b := &Bridge{dataDir: dataDir}
	os.MkdirAll(filepath.Join(dataDir, "media"), 0755)
	os.MkdirAll(filepath.Join(dataDir, "nested"), 0755)
	write := func(rel string) string {
		p := filepath.Join(dataDir, rel)
		os.WriteFile(p, []byte("x"), 0644)
		return p
	}
	staged := write("send_media_1700000000.jpg")
	voice := write("voice_1700000000.ogg")
	downloaded := write("media/3EB0ABC.jpg")
	secret := write("messages.db")
	nested := write("nested/send_media_1.jpg")
	link := filepath.Join(dataDir, "send_media_link.jpg")
	os.Symlink(secret, link)

	for _, ok := range []string{staged, voice, downloaded} {
		if err := b.sendableFile(ok); err != nil {
			t.Errorf("sendableFile(%s) = %v, want allowed", ok, err)
		}
	}
	for _, bad := range []string{
		secret,
		nested,
		link,
		filepath.Join(dataDir, "media", "..", "messages.db"),
		filepath.Join(dataDir, "send_media_missing.jpg"),
		"send_media_1700000000.jpg",
		"/etc/hosts",
		filepath.Join(dataDir, "databases", "signal_protocol.db"),
	} {
		if err := b.sendableFile(bad); err == nil {
			t.Errorf("sendableFile(%s) allowed, want refused", bad)
		}
	}
}

func TestUploadAndSend_refusesUnstagedPathWithoutARow(t *testing.T) {
	b := newClientBridge(t)
	b.dataDir = t.TempDir()
	secret := filepath.Join(b.dataDir, "whatsmeow.db")
	os.WriteFile(secret, []byte("keys"), 0600)

	if _, _, err := b.UploadAndSend("447700900001@s.whatsapp.net", secret, "application/octet-stream", "", ""); err == nil {
		t.Fatal("UploadAndSend sent an arbitrary file")
	}
	if n := rowCount(t, b, `SELECT COUNT(*) FROM messages`); n != 0 {
		t.Errorf("a refused send left %d rows", n)
	}
}

func TestRetryMessage_refusesUnstagedPath(t *testing.T) {
	b := newClientBridge(t)
	b.dataDir = t.TempDir()
	const jid = "447700900001@s.whatsapp.net"
	b.UpsertConversation(jid, named("Alice"), "", 0)
	b.InsertMessage(&MessageRow{
		ID: "f1", ConversationJID: jid, SenderJID: ownJID.String(), Timestamp: 1, ContentType: "document",
		MediaURL: nullString("/data/data/app.photon/databases/signal_protocol.db"), IsFromMe: true, Status: "failed",
	})

	err := b.RetryMessage(context.Background(), "f1")
	if err == nil || !strings.Contains(err.Error(), "refusing") {
		t.Errorf("RetryMessage = %v, want a refusal", err)
	}
	if got := messageStatus(t, b, "f1"); got != "failed" {
		t.Errorf("status = %q, want failed", got)
	}
}

func TestServeRequests_slowRequestDoesNotBlockOthers(t *testing.T) {
	release := make(chan struct{})
	handle := func(_ context.Context, msg Envelope) Envelope {
		if msg.Type == "send_media" {
			<-release // an upload that takes a while
		}
		return Envelope{ID: msg.ID, Type: msg.Type + "_response"}
	}
	replies := make(chan Envelope, 2)
	input := `{"id":"slow","type":"send_media"}` + "\n" + `{"id":"fast","type":"mark_read"}` + "\n"

	done := make(chan error, 1)
	go func() {
		done <- serveRequests(context.Background(), strings.NewReader(input), handle, func(m Envelope) { replies <- m }, func(error) {})
	}()

	select {
	case first := <-replies:
		if first.ID != "fast" {
			t.Fatalf("first reply = %s, want fast", first.ID)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("a slow request blocked the next one")
	}
	close(release)
	if second := <-replies; second.ID != "slow" {
		t.Errorf("second reply = %s, want slow", second.ID)
	}
	if err := <-done; err != nil {
		t.Errorf("serveRequests = %v", err)
	}
}
