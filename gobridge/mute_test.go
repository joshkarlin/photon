package main

import (
	"context"
	"database/sql"
	"testing"
	"time"

	"go.mau.fi/whatsmeow/proto/waSyncAction"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
	"google.golang.org/protobuf/proto"
)

func TestMutedUntil(t *testing.T) {
	mute := func(muted bool, endMs int64) *waSyncAction.MuteAction {
		return &waSyncAction.MuteAction{Muted: proto.Bool(muted), MuteEndTimestamp: proto.Int64(endMs)}
	}
	cases := []struct {
		name string
		act  *waSyncAction.MuteAction
		want int64
	}{
		{"no action", nil, 0},
		{"unmuted", mute(false, 1_900_000_000_000), 0},
		{"always (-1)", mute(true, -1), -1},
		{"muted with no end", &waSyncAction.MuteAction{Muted: proto.Bool(true)}, -1},
		{"until a time, in ms", mute(true, 1_900_000_000_000), 1_900_000_000},
		{"partial second rounds up", mute(true, 1_900_000_000_001), 1_900_000_001},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := mutedUntil(tc.act); got != tc.want {
				t.Errorf("mutedUntil = %d, want %d", got, tc.want)
			}
		})
	}
}

func mutedUntilColumn(t *testing.T, b *Bridge, jid string) int64 {
	t.Helper()
	var until int64
	if err := b.msgDB.QueryRow(`SELECT muted_until FROM conversations WHERE jid = ?`, jid).Scan(&until); err != nil {
		t.Fatalf("muted_until for %s: %v", jid, err)
	}
	return until
}

func TestHandleMute_storesEndTime(t *testing.T) {
	b := newClientBridge(t)
	chat := types.NewJID("447700900001", types.DefaultUserServer)

	// Muted for 8 hours from the phone; the event can precede any message.
	b.HandleEvent(&events.Mute{JID: chat, Action: &waSyncAction.MuteAction{
		Muted: proto.Bool(true), MuteEndTimestamp: proto.Int64(1_900_000_000_000),
	}})
	if got := mutedUntilColumn(t, b, chat.String()); got != 1_900_000_000 {
		t.Errorf("muted_until = %d", got)
	}

	b.HandleEvent(&events.Mute{JID: chat, Action: &waSyncAction.MuteAction{Muted: proto.Bool(false)}})
	if got := mutedUntilColumn(t, b, chat.String()); got != 0 {
		t.Errorf("muted_until after unmute = %d", got)
	}
}

func TestMigrateMessageDB_addsMutedUntilInPlace(t *testing.T) {
	db, err := sql.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { db.Close() })
	// A conversations table as shipped before muted_until, with real data.
	if _, err := db.Exec(`CREATE TABLE conversations (
		jid TEXT PRIMARY KEY, name TEXT, is_group INTEGER NOT NULL DEFAULT 0,
		last_message_id TEXT, last_timestamp INTEGER NOT NULL DEFAULT 0,
		unread_count INTEGER NOT NULL DEFAULT 0, is_muted INTEGER NOT NULL DEFAULT 0,
		avatar_url TEXT, updated_at INTEGER NOT NULL DEFAULT 0)`); err != nil {
		t.Fatal(err)
	}
	db.Exec(`INSERT INTO conversations (jid, name, is_muted, unread_count) VALUES ('m@s.whatsapp.net', 'Muted', 1, 3), ('u@s.whatsapp.net', 'Open', 0, 0)`)

	b := &Bridge{msgDB: db, log: waLog.Noop}
	for i := 0; i < 2; i++ { // the second run must be a no-op
		if err := b.InitMessageDB(context.Background()); err != nil {
			t.Fatalf("InitMessageDB run %d: %v", i+1, err)
		}
	}
	if got := mutedUntilColumn(t, b, "m@s.whatsapp.net"); got != -1 {
		t.Errorf("previously muted chat: muted_until = %d, want -1 until backfilled", got)
	}
	if got := mutedUntilColumn(t, b, "u@s.whatsapp.net"); got != 0 {
		t.Errorf("unmuted chat: muted_until = %d, want 0", got)
	}
	if got := unreadCount(t, b, "m@s.whatsapp.net"); got != 3 {
		t.Errorf("migration lost data: unread = %d", got)
	}
}

func TestBackfillMutedUntil(t *testing.T) {
	ctx := context.Background()
	b := newClientBridge(t)
	timed := types.NewJID("447700900001", types.DefaultUserServer)
	timedLID := types.NewJID("11223344", types.HiddenUserServer)
	always := types.NewJID("447700900002", types.DefaultUserServer)
	b.client.Store.LIDs.PutLIDMapping(ctx, timedLID, timed)

	// An old build stored the 8h mute as a bare flag (now -1), keyed the
	// mute event by LID, and stored an "always" mute as unmuted.
	b.UpdateMute(timed.String(), -1)
	b.UpdateMute(always.String(), 0)
	b.client.Store.ChatSettings.PutPinned(ctx, timed, true) // a settings row without the mute
	b.client.Store.ChatSettings.PutMutedUntil(ctx, timedLID, time.Unix(1_900_000_000, 0))
	b.client.Store.ChatSettings.PutMutedUntil(ctx, always, store.MutedForever)

	b.BackfillMutedUntil(ctx)

	if got := mutedUntilColumn(t, b, timed.String()); got != 1_900_000_000 {
		t.Errorf("timed mute = %d, want its real end", got)
	}
	if got := mutedUntilColumn(t, b, always.String()); got != -1 {
		t.Errorf("always mute = %d, want -1", got)
	}
}
