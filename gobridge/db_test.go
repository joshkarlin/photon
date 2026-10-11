package main

import (
	"context"
	"database/sql"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"go.mau.fi/whatsmeow/types"
	waLog "go.mau.fi/whatsmeow/util/log"
)

// A write transaction that reads first must not fail because another
// connection committed in between: that's SQLITE_BUSY_SNAPSHOT, which no
// busy timeout retries. Revokes and LID merges are shaped like this.
func TestMessageDBDSN_readThenWriteTransactionSurvivesAConcurrentWrite(t *testing.T) {
	registerFKDriver()
	db, err := sql.Open("sqlite_fk", messageDBDSN(t.TempDir()))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	b := &Bridge{msgDB: db, log: waLog.Noop}
	if err := b.InitMessageDB(context.Background()); err != nil {
		t.Fatal(err)
	}

	tx, err := db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	var n int
	if err := tx.QueryRow(`SELECT COUNT(*) FROM conversations`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	other := make(chan error, 1)
	go func() {
		_, err := db.Exec(`INSERT INTO conversations (jid) VALUES ('other@s.whatsapp.net')`)
		other <- err
	}()
	time.Sleep(200 * time.Millisecond) // a deferred transaction lets that write commit here
	if _, err := tx.Exec(`INSERT INTO conversations (jid) VALUES ('mine@s.whatsapp.net')`); err != nil {
		t.Fatalf("write after read: %v", err)
	}
	if err := tx.Commit(); err != nil {
		t.Fatalf("commit: %v", err)
	}
	if err := <-other; err != nil {
		t.Fatalf("the other connection's write: %v", err)
	}
}

// A revoke that hits a locked database is applied once the lock goes, not
// dropped: WhatsApp never sends it again.
func TestApplyRevoke_retriesWhenTheDatabaseIsBusy(t *testing.T) {
	dir := t.TempDir()
	dsn := filepath.Join(dir, "messages.db") + "?_txlock=immediate"
	// No busy timeout, so a locked database fails at once.
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	b := &Bridge{msgDB: db, log: waLog.Noop, dataDir: dir, retryDelay: 20 * time.Millisecond}
	if err := b.InitMessageDB(context.Background()); err != nil {
		t.Fatal(err)
	}
	sent := captureEvents(b)
	alice := types.NewJID("447700900001", types.DefaultUserServer)
	b.UpsertConversation(alice.String(), named("Alice"), "m1", 100)
	b.InsertMessage(&MessageRow{ID: "m1", ConversationJID: alice.String(), SenderJID: alice.String(), Timestamp: 100, ContentType: "text", Status: "sent"})
	b.IncrementUnread(alice.String())

	locker, err := sql.Open("sqlite", dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { locker.Close() })
	lock, err := locker.Begin()
	if err != nil {
		t.Fatal(err)
	}
	b.applyRevoke(alice.String(), revokeEvent(alice, alice, "r1", "m1", 101).Info, "m1")
	if got := messageStatus(t, b, "m1"); got == "deleted" {
		t.Fatal("revoke applied through the lock")
	}
	time.Sleep(50 * time.Millisecond)
	lock.Rollback()

	deadline := time.Now().Add(5 * time.Second)
	for messageStatus(t, b, "m1") != "deleted" {
		if time.Now().After(deadline) {
			t.Fatal("revoke never applied after the lock went")
		}
		time.Sleep(10 * time.Millisecond)
	}
	for len(sent.ofType("message_revoked")) == 0 {
		if time.Now().After(deadline) {
			t.Fatal("no message_revoked after the retry")
		}
		time.Sleep(10 * time.Millisecond)
	}
	if got := unreadCount(t, b, alice.String()); got != 0 {
		t.Errorf("unread = %d, want 0", got)
	}
}

func TestRetry(t *testing.T) {
	calls := 0
	err := retry(5, time.Millisecond, func() error {
		calls++
		if calls < 3 {
			return errors.New("busy")
		}
		return nil
	})
	if err != nil || calls != 3 {
		t.Errorf("retry = %v after %d calls, want success on the 3rd", err, calls)
	}

	calls = 0
	if err := retry(4, time.Millisecond, func() error { calls++; return errors.New("busy") }); err == nil || calls != 4 {
		t.Errorf("retry = %v after %d calls, want the error after 4", err, calls)
	}
}

// A messages.db from before name ranks gains the columns in place, keeps its
// names (unranked, so any ranked write replaces them) and loses the numbers
// and JIDs older builds stored as names.
func TestMigrateNameRanks_upgradesInPlace(t *testing.T) {
	db, err := sql.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { db.Close() })
	const grp = "120363000000000001@g.us"
	for _, q := range []string{
		`CREATE TABLE conversations (jid TEXT PRIMARY KEY, name TEXT, is_group INTEGER NOT NULL DEFAULT 0,
			last_message_id TEXT, last_timestamp INTEGER NOT NULL DEFAULT 0, unread_count INTEGER NOT NULL DEFAULT 0,
			is_muted INTEGER NOT NULL DEFAULT 0, avatar_url TEXT, updated_at INTEGER NOT NULL DEFAULT 0,
			muted_until INTEGER NOT NULL DEFAULT 0)`,
		`CREATE TABLE participants (conversation_jid TEXT NOT NULL, jid TEXT NOT NULL, display_name TEXT,
			role TEXT DEFAULT 'member', PRIMARY KEY (conversation_jid, jid))`,
		`INSERT INTO conversations (jid, name) VALUES
			('447700900001@s.whatsapp.net', 'Alice'),
			('447700900002@s.whatsapp.net', '+447700900002'),
			('11223344@lid', '11223344'),
			('447700900006@s.whatsapp.net', '447700900006@s.whatsapp.net'),
			('` + grp + `', 'Book club'),
			('447700900003@s.whatsapp.net', NULL)`,
		`INSERT INTO participants (conversation_jid, jid, display_name) VALUES
			('` + grp + `', '447700900004@s.whatsapp.net', 'Dave'),
			('` + grp + `', '447700900005@s.whatsapp.net', '+44 7700 900005')`,
		`PRAGMA user_version = 2`,
	} {
		if _, err := db.Exec(q); err != nil {
			t.Fatalf("%s: %v", q, err)
		}
	}
	b := &Bridge{msgDB: db, log: waLog.Noop}
	if err := b.InitMessageDB(context.Background()); err != nil {
		t.Fatal(err)
	}

	for jid, want := range map[string]string{
		"447700900001@s.whatsapp.net": "Alice",
		"447700900002@s.whatsapp.net": "",
		"11223344@lid":                "",
		"447700900006@s.whatsapp.net": "",
		grp:                           "Book club",
		"447700900003@s.whatsapp.net": "",
	} {
		if got := conversationRank(t, b, jid); got != (rankedName{name: want}) {
			t.Errorf("%s = %+v, want %q unranked", jid, got, want)
		}
	}
	for jid, want := range map[string]rankedName{
		"447700900004@s.whatsapp.net": {name: "Dave"},
		"447700900005@s.whatsapp.net": {},
	} {
		if got := b.storedParticipantName(grp, jid); got != want {
			t.Errorf("participant %s = %+v, want %+v", jid, got, want)
		}
	}
	if got := rowCount(t, b, `PRAGMA user_version`); got != len(migrations) {
		t.Errorf("user_version = %d, want %d", got, len(migrations))
	}

	// Any ranked name replaces an unranked one.
	b.UpsertConversation("447700900001@s.whatsapp.net", named("ally"), "", 0)
	if got := conversationRank(t, b, "447700900001@s.whatsapp.net"); got != named("ally") {
		t.Errorf("after a push name: %+v", got)
	}
}
