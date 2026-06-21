package main

import (
	"context"
	"database/sql"
	"testing"

	_ "modernc.org/sqlite"
	"go.mau.fi/whatsmeow/types"
	waLog "go.mau.fi/whatsmeow/util/log"
)

// newTestBridge returns a Bridge backed by a fresh in-memory SQLite DB with the
// message schema created. Only msgDB-touching logic can be exercised (no
// whatsmeow client), which is exactly the DB-level code these tests cover.
func newTestBridge(t *testing.T) *Bridge {
	t.Helper()
	db, err := sql.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatalf("open in-memory db: %v", err)
	}
	// database/sql treats each ":memory:" connection as a separate DB; pin to a
	// single connection so every query hits the same one.
	db.SetMaxOpenConns(1)
	b := &Bridge{msgDB: db, log: waLog.Noop}
	if err := b.InitMessageDB(context.Background()); err != nil {
		t.Fatalf("init schema: %v", err)
	}
	t.Cleanup(func() { db.Close() })
	return b
}

func TestBestContactName(t *testing.T) {
	cases := []struct {
		name          string
		contact       types.ContactInfo
		eventPushName string
		wantName      string
		wantAuth      bool
	}{
		{"address book wins over everything", types.ContactInfo{FullName: "Alice", PushName: "ally", BusinessName: "ACME"}, "live", "Alice", true},
		{"event push name beats stored push name", types.ContactInfo{PushName: "stored"}, "live", "live", false},
		{"stored push name when no event name", types.ContactInfo{PushName: "stored"}, "", "stored", false},
		{"business name as last resort", types.ContactInfo{BusinessName: "ACME"}, "", "ACME", false},
		{"nothing known", types.ContactInfo{}, "", "", false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			gotName, gotAuth := bestContactName(tc.contact, tc.eventPushName)
			if gotName != tc.wantName || gotAuth != tc.wantAuth {
				t.Errorf("bestContactName = (%q, %v), want (%q, %v)", gotName, gotAuth, tc.wantName, tc.wantAuth)
			}
		})
	}
}

func TestPurgeNumberPlaceholders(t *testing.T) {
	b := newTestBridge(t)
	const grp = "123@g.us"
	// Mix of real names and number placeholders, plus a name that merely starts
	// with a "+digit" but contains letters (must be preserved).
	seed := []struct{ jid, name string }{
		{"a@s.whatsapp.net", "Alice"},
		{"b@s.whatsapp.net", "+14155550001"}, // placeholder → delete
		{"c@s.whatsapp.net", "+1 Dad"},       // real name with digits → keep
		{"d@s.whatsapp.net", "+447700900123"}, // placeholder → delete
		{"e@s.whatsapp.net", "Bob"},
	}
	for _, s := range seed {
		if err := b.UpsertParticipant(grp, s.jid, s.name, "member"); err != nil {
			t.Fatalf("seed %s: %v", s.jid, err)
		}
	}
	// A placeholder in a *different* group must be untouched.
	if err := b.UpsertParticipant("999@g.us", "z@s.whatsapp.net", "+15550000000", "member"); err != nil {
		t.Fatal(err)
	}

	if got := b.purgeNumberPlaceholders(grp); got != 2 {
		t.Fatalf("purged = %d, want 2", got)
	}

	got := participantNames(t, b, grp)
	want := map[string]string{
		"a@s.whatsapp.net": "Alice",
		"c@s.whatsapp.net": "+1 Dad",
		"e@s.whatsapp.net": "Bob",
	}
	if len(got) != len(want) {
		t.Fatalf("remaining = %v, want %v", got, want)
	}
	for k, v := range want {
		if got[k] != v {
			t.Errorf("%s = %q, want %q", k, got[k], v)
		}
	}
	// Other group's placeholder survived (purge is scoped per conversation).
	if other := participantNames(t, b, "999@g.us"); other["z@s.whatsapp.net"] != "+15550000000" {
		t.Errorf("cross-group purge: other group row was affected: %v", other)
	}
}

func TestUpsertParticipantCoalesce(t *testing.T) {
	b := newTestBridge(t)
	const grp = "123@g.us"
	const jid = "a@s.whatsapp.net"

	b.UpsertParticipant(grp, jid, "Alice", "member")
	// An empty name must NOT clobber an existing name.
	b.UpsertParticipant(grp, jid, "", "member")
	if got := participantNames(t, b, grp)[jid]; got != "Alice" {
		t.Errorf("after empty upsert = %q, want Alice (empty must not clobber)", got)
	}
	// A non-empty name overwrites (this is what lets a real name replace a
	// placeholder, and — the bug we fixed — would let a number clobber a name,
	// which is why the number paths were removed at the call sites).
	b.UpsertParticipant(grp, jid, "Alice Smith", "member")
	if got := participantNames(t, b, grp)[jid]; got != "Alice Smith" {
		t.Errorf("after rename = %q, want Alice Smith", got)
	}
}

func TestCanonicalSenderJID(t *testing.T) {
	// No whatsmeow client needed: resolveLIDJID short-circuits for non-LID
	// (phone) servers, so only the device-suffix stripping runs.
	b := &Bridge{log: waLog.Noop}
	cases := []struct{ in, want string }{
		{"447951542239:4@s.whatsapp.net", "447951542239@s.whatsapp.net"}, // strip device agent
		{"447505629916@s.whatsapp.net", "447505629916@s.whatsapp.net"},   // already canonical
		{"447505629916:12@s.whatsapp.net", "447505629916@s.whatsapp.net"},
	}
	for _, tc := range cases {
		jid, err := types.ParseJID(tc.in)
		if err != nil {
			t.Fatalf("parse %q: %v", tc.in, err)
		}
		if got := b.canonicalSenderJID(jid); got != tc.want {
			t.Errorf("canonicalSenderJID(%q) = %q, want %q", tc.in, got, tc.want)
		}
		if got := b.canonicalSenderString(tc.in); got != tc.want {
			t.Errorf("canonicalSenderString(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestSendersForMessages(t *testing.T) {
	b := newTestBridge(t)
	const grp = "123@g.us"
	b.UpsertConversation(grp, "Group", true, "", 0)
	rows := []*MessageRow{
		{ID: "m1", ConversationJID: grp, SenderJID: "a@s.whatsapp.net", Timestamp: 1, ContentType: "text"},
		{ID: "m2", ConversationJID: grp, SenderJID: "b@s.whatsapp.net", Timestamp: 2, ContentType: "text"},
	}
	if err := b.InsertMessages(rows); err != nil {
		t.Fatalf("insert: %v", err)
	}
	got := b.SendersForMessages([]string{"m1", "m2", "missing"})
	if got["m1"] != "a@s.whatsapp.net" || got["m2"] != "b@s.whatsapp.net" {
		t.Errorf("senders = %v", got)
	}
	if _, ok := got["missing"]; ok {
		t.Errorf("unexpected entry for missing id: %v", got)
	}
}

// participantNames reads the participants table into a jid→display_name map.
func participantNames(t *testing.T, b *Bridge, groupJID string) map[string]string {
	t.Helper()
	rows, err := b.msgDB.Query(`SELECT jid, COALESCE(display_name, '') FROM participants WHERE conversation_jid = ?`, groupJID)
	if err != nil {
		t.Fatalf("query participants: %v", err)
	}
	defer rows.Close()
	out := map[string]string{}
	for rows.Next() {
		var jid, name string
		if err := rows.Scan(&jid, &name); err != nil {
			t.Fatal(err)
		}
		out[jid] = name
	}
	return out
}
