package main

import (
	"context"
	"database/sql"
	"testing"

	"go.mau.fi/whatsmeow/types"
	waLog "go.mau.fi/whatsmeow/util/log"
	_ "modernc.org/sqlite"
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
		{"verified business name beats push names", types.ContactInfo{BusinessName: "ACME", PushName: "acme"}, "live", "ACME", true},
		{"business name alone", types.ContactInfo{BusinessName: "ACME"}, "", "ACME", true},
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
	b.UpsertConversation(grp, "Group", "", 0)
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

func TestPruneMessages_capsPerConversationAndRemovesEmptyRows(t *testing.T) {
	b := newTestBridge(t)
	b.retention = RetentionConfig{MaxMessages: 2, MaxDays: 0}

	b.UpsertConversation("chat@s.whatsapp.net", "Alice", "", 0)
	b.UpsertConversation("ghost@s.whatsapp.net", "Ghost", "", 0) // no messages
	if err := b.InsertMessages([]*MessageRow{
		{ID: "m1", ConversationJID: "chat@s.whatsapp.net", SenderJID: "a", Timestamp: 1, ContentType: "text"},
		{ID: "m2", ConversationJID: "chat@s.whatsapp.net", SenderJID: "a", Timestamp: 2, ContentType: "text"},
		{ID: "m3", ConversationJID: "chat@s.whatsapp.net", SenderJID: "a", Timestamp: 3, ContentType: "text"},
	}); err != nil {
		t.Fatalf("insert: %v", err)
	}

	b.pruneMessages()

	// Per-conversation cap keeps the newest 2 (m2, m3); oldest pruned.
	var kept int
	b.msgDB.QueryRow(`SELECT COUNT(*) FROM messages WHERE conversation_jid='chat@s.whatsapp.net'`).Scan(&kept)
	if kept != 2 {
		t.Errorf("messages kept = %d, want 2", kept)
	}
	var hasOldest int
	b.msgDB.QueryRow(`SELECT COUNT(*) FROM messages WHERE id='m1'`).Scan(&hasOldest)
	if hasOldest != 0 {
		t.Errorf("oldest message m1 should have been pruned")
	}

	// The empty conversation is removed; the one with messages survives.
	var jids []string
	rows, _ := b.msgDB.Query(`SELECT jid FROM conversations ORDER BY jid`)
	defer rows.Close()
	for rows.Next() {
		var j string
		rows.Scan(&j)
		jids = append(jids, j)
	}
	if len(jids) != 1 || jids[0] != "chat@s.whatsapp.net" {
		t.Errorf("conversations after prune = %v, want [chat@s.whatsapp.net]", jids)
	}
}

// is_group follows the JID's server on every write, whichever path makes the
// row: an incoming message, a send's nameless preview bump (which once flipped
// a group to a DM), or a mute that arrives before any message.
func TestConversation_isGroupFollowsTheJID(t *testing.T) {
	b := newTestBridge(t)
	b.UpdateMute("123-456@g.us", -1)
	b.UpsertConversation("123-456@g.us", "", "m1", 1000)
	b.UpsertConversation("789@s.whatsapp.net", "Bob", "dm1", 5000)
	b.UpsertConversation("789@s.whatsapp.net", "", "dm2", 6000)
	b.UpdateMute("999@s.whatsapp.net", -1)

	isGroup := func(jid string) int {
		return rowCount(t, b, `SELECT is_group FROM conversations WHERE jid = ?`, jid)
	}
	for jid, want := range map[string]int{"123-456@g.us": 1, "789@s.whatsapp.net": 0, "999@s.whatsapp.net": 0} {
		if got := isGroup(jid); got != want {
			t.Errorf("%s is_group = %d, want %d", jid, got, want)
		}
	}

	// A row an older build got wrong is put right by the chat's next write.
	b.msgDB.Exec(`UPDATE conversations SET is_group = 0 WHERE jid = '123-456@g.us'`)
	b.UpsertConversation("123-456@g.us", "", "m2", 2000)
	if got := isGroup("123-456@g.us"); got != 1 {
		t.Errorf("stale is_group = %d after the next write, want 1", got)
	}
}

func TestDeleteMessageRow_movesThePreviewBack(t *testing.T) {
	b := newTestBridge(t)
	const jid = "789@s.whatsapp.net"
	b.UpsertConversation(jid, "Bob", "m2", 200)
	b.InsertMessages([]*MessageRow{
		{ID: "m1", ConversationJID: jid, SenderJID: jid, Timestamp: 100, ContentType: "text"},
		{ID: "m2", ConversationJID: jid, SenderJID: "me", Timestamp: 200, ContentType: "text", IsFromMe: true},
	})

	b.DeleteMessageRow("m2")

	var ts int64
	var last string
	b.msgDB.QueryRow(`SELECT last_timestamp, last_message_id FROM conversations WHERE jid = ?`, jid).Scan(&ts, &last)
	if ts != 100 || last != "m1" {
		t.Errorf("preview = (%d, %q), want (100, m1)", ts, last)
	}
}

func TestApplyMentions(t *testing.T) {
	names := map[string]string{
		"447111@s.whatsapp.net": "Alice",
		"447222@s.whatsapp.net": "Bob",
	}
	nameFor := func(jid types.JID) string { return names[jid.String()] }

	got := applyMentions(
		"hey @447111 and @447222, ping @447999",
		[]string{"447111@s.whatsapp.net", "447222@s.whatsapp.net", "447999@s.whatsapp.net"},
		nameFor,
	)
	// Known mentions become names; the unknown one stays a number.
	want := "hey @Alice and @Bob, ping @447999"
	if got != want {
		t.Errorf("applyMentions = %q, want %q", got, want)
	}

	// No mentions / empty text are no-ops.
	if applyMentions("", []string{"447111@s.whatsapp.net"}, nameFor) != "" {
		t.Error("empty text should stay empty")
	}
	if applyMentions("plain text", nil, nameFor) != "plain text" {
		t.Error("no mentions should be a no-op")
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
