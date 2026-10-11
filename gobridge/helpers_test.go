package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waAdv"
	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
	"google.golang.org/protobuf/proto"
)

// ownJID is the paired account in client-backed tests.
var ownJID = types.NewJID("440000000000", types.DefaultUserServer)

// newClientBridge is newTestBridge plus an offline whatsmeow client backed by
// a throwaway session store, so handlers that consult the contact or LID
// stores run for real. Network calls fail fast with ErrNotConnected.
func newClientBridge(t *testing.T) *Bridge {
	t.Helper()
	b := newTestBridge(t)
	b.client = newTestClient(t)
	return b
}

// newTestClient is an offline whatsmeow client paired as ownJID, with a
// throwaway session store.
func newTestClient(t *testing.T) *whatsmeow.Client {
	t.Helper()
	ctx := context.Background()
	registerFKDriver()
	container, err := sqlstore.New(ctx, "sqlite_fk", filepath.Join(t.TempDir(), "whatsmeow.db"), waLog.Noop)
	if err != nil {
		t.Fatalf("session store: %v", err)
	}
	t.Cleanup(func() { container.Close() })
	device := container.NewDevice()
	own := ownJID
	device.ID = &own
	// The schema only checks key lengths; nothing here verifies signatures.
	device.Account = &waAdv.ADVSignedDeviceIdentity{
		Details:             []byte{0},
		AccountSignature:    make([]byte, 64),
		AccountSignatureKey: make([]byte, 32),
		DeviceSignature:     make([]byte, 64),
	}
	if err := container.PutDevice(ctx, device); err != nil {
		t.Fatalf("put device: %v", err)
	}
	return whatsmeow.NewClient(device, waLog.Noop)
}

// eventLog records every event the bridge broadcasts.
type eventLog struct {
	mu  sync.Mutex
	all []Envelope
}

func captureEvents(b *Bridge) *eventLog {
	l := &eventLog{}
	b.emit = func(m Envelope) {
		l.mu.Lock()
		l.all = append(l.all, m)
		l.mu.Unlock()
	}
	return l
}

// ofType returns the decoded payloads of every event of the given type.
func (l *eventLog) ofType(typ string) []map[string]any {
	l.mu.Lock()
	defer l.mu.Unlock()
	var out []map[string]any
	for _, m := range l.all {
		if m.Type != typ {
			continue
		}
		var p map[string]any
		json.Unmarshal(m.Payload, &p)
		out = append(out, p)
	}
	return out
}

// textEvent builds a live incoming DM/group message event.
func textEvent(chat, sender types.JID, id, text string, ts int64) *events.Message {
	return messageEvent(chat, sender, id, ts, &waE2E.Message{Conversation: proto.String(text)})
}

func messageEvent(chat, sender types.JID, id string, ts int64, msg *waE2E.Message) *events.Message {
	return &events.Message{
		Info: types.MessageInfo{
			MessageSource: types.MessageSource{
				Chat:     chat,
				Sender:   sender,
				IsFromMe: sender.User == ownJID.User,
				IsGroup:  chat.Server == types.GroupServer,
			},
			ID:        id,
			Timestamp: time.Unix(ts, 0),
		},
		Message: msg,
	}
}

func rowCount(t *testing.T, b *Bridge, query string, args ...any) int {
	t.Helper()
	var n int
	if err := b.msgDB.QueryRow(query, args...).Scan(&n); err != nil {
		t.Fatalf("%s: %v", query, err)
	}
	return n
}

func textBody(t *testing.T, b *Bridge, id string) string {
	t.Helper()
	var s string
	if err := b.msgDB.QueryRow(`SELECT COALESCE(text_body, '') FROM messages WHERE id = ?`, id).Scan(&s); err != nil {
		t.Fatalf("text for %s: %v", id, err)
	}
	return s
}

func nullString(s string) sql.NullString { return sql.NullString{String: s, Valid: s != ""} }
