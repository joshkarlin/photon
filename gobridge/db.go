package main

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
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
			muted_until     INTEGER NOT NULL DEFAULT 0
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

// migrateMessageDB upgrades existing installs in place. Each step is
// idempotent; CREATE TABLE IF NOT EXISTS above never alters an existing table.
func (b *Bridge) migrateMessageDB(ctx context.Context) error {
	// muted_until: 0 = not muted, -1 = muted forever, otherwise the unix
	// second the mute ends. Rows muted before this column existed have no
	// known end, so start them as "forever"; BackfillMutedUntil then copies
	// the real end time from whatsmeow's own chat settings.
	added, err := b.addColumnIfMissing(ctx, "conversations", "muted_until", "INTEGER NOT NULL DEFAULT 0")
	if err != nil {
		return err
	}
	if added {
		if _, err := b.msgDB.ExecContext(ctx, `UPDATE conversations SET muted_until = -1 WHERE is_muted = 1`); err != nil {
			return fmt.Errorf("migrate muted_until: %w", err)
		}
		b.log.Infof("Migrated conversations: added muted_until")
	}
	return nil
}

// addColumnIfMissing adds a column unless the table already has it.
func (b *Bridge) addColumnIfMissing(ctx context.Context, table, column, def string) (added bool, err error) {
	rows, err := b.msgDB.QueryContext(ctx, `SELECT name FROM pragma_table_info(?)`, table)
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
	if _, err := b.msgDB.ExecContext(ctx, `ALTER TABLE `+table+` ADD COLUMN `+column+` `+def); err != nil {
		return false, fmt.Errorf("add %s.%s: %w", table, column, err)
	}
	return true, nil
}

// RepairConversationMetadata fixes existing conversation rows whose is_group or
// preview drifted from the source of truth. Runs on connect; idempotent.
//   - is_group from the JID server (g.us → group, s.whatsapp.net → DM), undoing
//     any old misclassification the sticky upsert had made permanent.
//   - last_timestamp / last_message_id from the newest actual message, undoing
//     history sync having pinned the preview to an older (or skipped) message.
func (b *Bridge) RepairConversationMetadata() {
	b.msgDB.Exec(`UPDATE conversations SET is_group = 1 WHERE jid LIKE '%@g.us' AND is_group <> 1`)
	b.msgDB.Exec(`UPDATE conversations SET is_group = 0 WHERE jid LIKE '%@s.whatsapp.net' AND is_group <> 0`)
	b.msgDB.Exec(`
		UPDATE conversations SET
			last_timestamp  = (SELECT MAX(timestamp) FROM messages WHERE conversation_jid = conversations.jid),
			last_message_id = (SELECT id FROM messages WHERE conversation_jid = conversations.jid ORDER BY timestamp DESC, _rowid_ DESC LIMIT 1)
		WHERE EXISTS (SELECT 1 FROM messages WHERE conversation_jid = conversations.jid)
	`)
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

// sqlInList returns "?,?,…" for n placeholders.
func sqlInList(n int) string {
	if n <= 0 {
		return ""
	}
	return "?" + strings.Repeat(",?", n-1)
}
