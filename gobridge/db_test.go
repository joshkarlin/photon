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
	b.UpsertConversation(alice.String(), "Alice", "m1", 100)
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
