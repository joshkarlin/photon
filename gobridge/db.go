package main

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"time"
)

// InitMessageDB creates the message database schema if it doesn't exist.
func (b *Bridge) InitMessageDB(ctx context.Context) error {
	statements := []string{
		`CREATE TABLE IF NOT EXISTS conversations (
			jid             TEXT PRIMARY KEY,
			name            TEXT,
			is_group        INTEGER NOT NULL DEFAULT 0,
			last_message_id TEXT,
			last_timestamp  INTEGER NOT NULL DEFAULT 0,
			unread_count    INTEGER NOT NULL DEFAULT 0,
			is_muted        INTEGER NOT NULL DEFAULT 0,
			avatar_url      TEXT,
			updated_at      INTEGER NOT NULL DEFAULT 0,
			muted_until     INTEGER NOT NULL DEFAULT 0,
			name_rank       INTEGER NOT NULL DEFAULT 0,
			disappearing_seconds INTEGER NOT NULL DEFAULT 0,
			disappearing_set_at  INTEGER NOT NULL DEFAULT 0
		)`,
		`CREATE TABLE IF NOT EXISTS messages (
			id              TEXT PRIMARY KEY,
			conversation_jid TEXT NOT NULL,
			sender_jid      TEXT NOT NULL,
			timestamp       INTEGER NOT NULL,
			content_type    TEXT NOT NULL,
			text_body       TEXT,
			media_url       TEXT,
			media_mime      TEXT,
			media_size      INTEGER,
			thumbnail_path  TEXT,
			sticker_pack_id TEXT,
			reply_to_id     TEXT,
			edit_version    INTEGER NOT NULL DEFAULT 0,
			is_from_me      INTEGER NOT NULL DEFAULT 0,
			status          TEXT NOT NULL DEFAULT 'sent',
			raw_proto       BLOB,
			expires_at      INTEGER NOT NULL DEFAULT 0,
			FOREIGN KEY (conversation_jid) REFERENCES conversations(jid)
		)`,
		`CREATE INDEX IF NOT EXISTS idx_messages_conv_ts ON messages(conversation_jid, timestamp DESC)`,
		`CREATE TABLE IF NOT EXISTS reactions (
			message_id  TEXT NOT NULL,
			sender_jid  TEXT NOT NULL,
			emoji       TEXT NOT NULL,
			timestamp   INTEGER NOT NULL,
			PRIMARY KEY (message_id, sender_jid)
		)`,
		`CREATE TABLE IF NOT EXISTS participants (
			conversation_jid TEXT NOT NULL,
			jid              TEXT NOT NULL,
			display_name     TEXT,
			role             TEXT DEFAULT 'member',
			name_rank        INTEGER NOT NULL DEFAULT 0,
			PRIMARY KEY (conversation_jid, jid)
		)`,
	}

	for i, stmt := range statements {
		if _, err := b.msgDB.ExecContext(ctx, stmt); err != nil {
			b.log.Errorf("Schema statement %d failed: %v", i, err)
			return fmt.Errorf("schema exec failed: %w", err)
		}
	}
	if err := b.migrateMessageDB(ctx); err != nil {
		return err
	}
	b.log.Infof("Message DB schema initialized (%d statements)", len(statements))
	return nil
}

// migrations upgrade an existing messages.db in place, in order. Each runs
// once, in its own transaction, and PRAGMA user_version counts those done; a
// fresh database runs them all against the empty tables InitMessageDB just
// made. Append only: a released step never changes or moves.
var migrations = []func(b *Bridge, ctx context.Context, tx *sql.Tx) error{
	(*Bridge).migrateMutedUntil,
	(*Bridge).migrateIncomingReadState,
	(*Bridge).migrateNameRanks,
	(*Bridge).migrateDisappearing,
	(*Bridge).migrateViewOnce,
}

// migrateMessageDB runs the migrations this database hasn't had yet.
// CREATE TABLE IF NOT EXISTS in InitMessageDB never alters an existing table.
func (b *Bridge) migrateMessageDB(ctx context.Context) error {
	var done int
	if err := b.msgDB.QueryRowContext(ctx, `PRAGMA user_version`).Scan(&done); err != nil {
		return fmt.Errorf("read schema version: %w", err)
	}
	for i := done; i < len(migrations); i++ {
		err := b.inTx(func(tx *sql.Tx) error {
			if err := migrations[i](b, ctx, tx); err != nil {
				return err
			}
			_, err := tx.ExecContext(ctx, fmt.Sprintf(`PRAGMA user_version = %d`, i+1))
			return err
		})
		if err != nil {
			return fmt.Errorf("migration %d: %w", i+1, err)
		}
		b.log.Infof("Applied message DB migration %d", i+1)
	}
	return nil
}

// migrateMutedUntil adds muted_until: 0 = not muted, -1 = muted forever,
// otherwise the unix second the mute ends. Builds before it kept only the
// is_muted flag, so a timed mute never lapsed and an "always" mute could read
// as unmuted. Those rows start as "forever", then take their real end time
// from whatsmeow's own chat settings, which recorded it all along. The copy
// runs here, once: whatsmeow can keep a stale entry under a chat's other JID
// form (LID vs phone) that would re-mute it on every start, and from here on
// mute events keep the column current. A database that already has the
// column (from a build that added it without a version) is left as it is.
func (b *Bridge) migrateMutedUntil(ctx context.Context, tx *sql.Tx) error {
	added, err := addColumnIfMissing(ctx, tx, "conversations", "muted_until", "INTEGER NOT NULL DEFAULT 0")
	if err != nil || !added {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE conversations SET muted_until = -1 WHERE is_muted = 1`); err != nil {
		return err
	}
	return b.backfillMutedUntil(ctx, tx)
}

// migrateIncomingReadState makes each chat's incoming rows agree with its
// unread count. Builds before per-row read state cleared only the count, so
// every message they stored kept status "sent", which now means unread. A
// revoke of one of those long-read messages then took it out of the count,
// clearing the badge of a newer message that really was unread. The newest
// unread_count incoming rows of each chat stay unread; older ones become read.
func (b *Bridge) migrateIncomingReadState(ctx context.Context, tx *sql.Tx) error {
	_, err := tx.ExecContext(ctx, `
		UPDATE messages SET status = 'read' WHERE _rowid_ IN (
			SELECT m.rid FROM (
				SELECT _rowid_ AS rid, conversation_jid,
					ROW_NUMBER() OVER (PARTITION BY conversation_jid ORDER BY timestamp DESC, _rowid_ DESC) AS n
				FROM messages WHERE is_from_me = 0 AND status NOT IN ('read', 'deleted')
			) m LEFT JOIN conversations c ON c.jid = m.conversation_jid
			WHERE m.n > COALESCE(c.unread_count, 0)
		)`)
	return err
}

// migrateNameRanks adds name_rank to conversations and participants: how
// strongly each stored name is backed (see rankNone and the ranks after it),
// so a weaker source never replaces a stronger one. Names stored before it
// start at rankNone, which any named write replaces, and the reconcile after
// connect re-ranks every DM and group member from whatsmeow's contact store.
// A name that is really a number or a JID (the "+<number>" an @lid DM was
// named with, say) is cleared, as Kotlin shows the number itself.
func (b *Bridge) migrateNameRanks(ctx context.Context, tx *sql.Tx) error {
	for _, table := range []string{"conversations", "participants"} {
		if _, err := addColumnIfMissing(ctx, tx, table, "name_rank", "INTEGER NOT NULL DEFAULT 0"); err != nil {
			return err
		}
	}
	for _, t := range []struct{ table, col string }{{"conversations", "name"}, {"participants", "display_name"}} {
		if err := cleanStoredNames(ctx, tx, t.table, t.col); err != nil {
			return err
		}
	}
	return nil
}

// cleanStoredNames puts every name in table.col through cleanName.
func cleanStoredNames(ctx context.Context, tx *sql.Tx, table, col string) error {
	rows, err := tx.QueryContext(ctx, `SELECT _rowid_, `+col+` FROM `+table+` WHERE COALESCE(`+col+`, '') <> ''`)
	if err != nil {
		return err
	}
	type fix struct {
		rowid int64
		name  string
	}
	var fixes []fix
	for rows.Next() {
		var f fix
		var name string
		if err := rows.Scan(&f.rowid, &name); err != nil {
			rows.Close()
			return err
		}
		if f.name = cleanName(name); f.name != name {
			fixes = append(fixes, f)
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, f := range fixes {
		if _, err := tx.ExecContext(ctx, `UPDATE `+table+` SET `+col+` = ?, name_rank = 0 WHERE _rowid_ = ?`, f.name, f.rowid); err != nil {
			return err
		}
	}
	return nil
}

// addColumnIfMissing adds a column unless the table already has it.
func addColumnIfMissing(ctx context.Context, tx *sql.Tx, table, column, def string) (added bool, err error) {
	rows, err := tx.QueryContext(ctx, `SELECT name FROM pragma_table_info(?)`, table)
	if err != nil {
		return false, fmt.Errorf("inspect %s: %w", table, err)
	}
	exists := false
	for rows.Next() {
		var name string
		if rows.Scan(&name) == nil && name == column {
			exists = true
		}
	}
	rows.Close()
	if exists {
		return false, nil
	}
	if _, err := tx.ExecContext(ctx, `ALTER TABLE `+table+` ADD COLUMN `+column+` `+def); err != nil {
		return false, fmt.Errorf("add %s.%s: %w", table, column, err)
	}
	return true, nil
}

// execer is a *sql.DB or *sql.Tx.
type execer interface {
	Exec(query string, args ...interface{}) (sql.Result, error)
}

// inTx runs fn in a transaction, committing only if it succeeds.
func (b *Bridge) inTx(fn func(*sql.Tx) error) error {
	tx, err := b.msgDB.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err := fn(tx); err != nil {
		return err
	}
	return tx.Commit()
}

// retry runs fn until it succeeds, at most attempts times, waiting delay
// before the second try and twice as long before each one after.
func retry(attempts int, delay time.Duration, fn func() error) error {
	err := fn()
	for i := 1; i < attempts && err != nil; i++ {
		time.Sleep(delay)
		delay *= 2
		err = fn()
	}
	return err
}

// sqlInList returns "?,?,…" for n placeholders.
func sqlInList(n int) string {
	if n <= 0 {
		return ""
	}
	return "?" + strings.Repeat(",?", n-1)
}
