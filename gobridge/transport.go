package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

// Kotlin and the bridge talk over the bridge process's stdin and stdout: one
// JSON envelope (WsMessage) per line in each direction; logs go to stderr.
// Only the app that spawned the bridge holds these pipes — the localhost
// WebSocket this replaced was open to every app on the device — and the bridge
// exits when stdin closes, so it can't outlive the app.

// lineWriter writes envelopes to one stream, one per line. Safe for
// concurrent use: each line is written whole.
type lineWriter struct {
	mu sync.Mutex
	w  io.Writer
}

func (lw *lineWriter) send(msg WsMessage) error {
	// encoding/json escapes newlines inside strings, so a line is one message.
	data, err := json.Marshal(msg)
	if err != nil {
		return err
	}
	data = append(data, '\n')
	lw.mu.Lock()
	defer lw.mu.Unlock()
	_, err = lw.w.Write(data)
	return err
}

// readLines calls handle with each envelope read from r, in order, until r
// ends (nil) or fails. Lines may be any length. A malformed line is reported
// to onBad and skipped.
func readLines(r io.Reader, handle func(WsMessage), onBad func(error)) error {
	br := bufio.NewReaderSize(r, 64*1024)
	for {
		line, err := br.ReadBytes('\n')
		if len(bytes.TrimSpace(line)) > 0 {
			var msg WsMessage
			if jerr := json.Unmarshal(line, &msg); jerr != nil {
				onBad(jerr)
			} else {
				handle(msg)
			}
		}
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
	}
}

// writeEvent is the transport behind BroadcastEvent.
func (b *Bridge) writeEvent(msg WsMessage) {
	if b.out == nil {
		return
	}
	if err := b.out.send(msg); err != nil {
		b.log.Warnf("Failed to send %s event: %v", msg.Type, err)
	}
}

// Serve answers requests read from r until it closes — the app exited, or is
// restarting the bridge. It first reports the current WhatsApp state, as a
// freshly connected client needs it.
func (b *Bridge) Serve(ctx context.Context, r io.Reader) error {
	state, reason := b.currentConnectionState()
	b.writeEvent(WsMessage{
		Type:    "connection_state",
		Payload: mustMarshal(ConnectionStateEvent{State: state, Reason: reason}),
	})
	return serveRequests(ctx, r, b.handleRequest, b.writeEvent, func(err error) {
		b.log.Warnf("Ignoring malformed request line: %v", err)
	})
}

// serveRequests runs handle for each request read from r and passes its
// response to reply. Each request runs in its own goroutine so a slow one
// (send_media uploads) doesn't hold up the rest; responses carry the request's
// ID, so they may go out in any order.
func serveRequests(ctx context.Context, r io.Reader, handle func(context.Context, WsMessage) WsMessage, reply func(WsMessage), onBad func(error)) error {
	return readLines(r, func(msg WsMessage) {
		go func() { reply(handle(ctx, msg)) }()
	}, onBad)
}

// sendableFile checks a path Kotlin asks the bridge to upload (send_media, or
// a retry of one). Only Photon's own staged files qualify — a picked file or
// voice note staged in the app's files dir (send_media_*, voice_*) — or media
// the bridge downloaded itself. Everything else in the sandbox, notably the
// databases and Signal's keys, is refused, as are symlinks.
func (b *Bridge) sendableFile(path string) error {
	if !filepath.IsAbs(path) {
		return fmt.Errorf("refusing to send %q: not an absolute path", path)
	}
	clean := filepath.Clean(path)
	dir, name := filepath.Dir(clean), filepath.Base(clean)
	staged := dir == filepath.Clean(b.dataDir) && (strings.HasPrefix(name, "send_media_") || strings.HasPrefix(name, "voice_"))
	downloaded := dir == filepath.Join(b.dataDir, "media")
	if !staged && !downloaded {
		return fmt.Errorf("refusing to send %q: not a staged or downloaded media file", path)
	}
	info, err := os.Lstat(clean)
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() {
		return fmt.Errorf("refusing to send %q: not a regular file", path)
	}
	return nil
}
