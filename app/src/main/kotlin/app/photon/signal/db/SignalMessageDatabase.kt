package app.photon.signal.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Photon's Signal message store: schema, migrations and change
 * notifications. The queries are extension functions grouped by concern in
 * this package: SignalConversations, SignalMessages, SignalReadState,
 * SignalContacts, SignalReactions, SignalCallRows and SignalRetention.
 */
class SignalMessageDatabase(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, 4,
) {
    companion object {
        const val DATABASE_NAME = "signal_messages.db"

        // Incoming-row `status` is read state, not delivery state:
        //  - STATUS_UNREAD: not yet seen anywhere; counted in unread_count.
        //  - STATUS_SEEN: viewed in Photon (unread_count already cleared) but
        //    the read sync to the user's other devices hasn't gone out yet.
        //  - STATUS_READ: read and synced, or read on another device.
        // Remote-deleted rows are 'deleted' and never counted.
        const val STATUS_UNREAD = "received"
        const val STATUS_SEEN = "seen"
        const val STATUS_READ = "read"
        const val STATUS_DELETED = "deleted"

        // Call log rows (see SignalCalls). Their ids aren't "{author}_{ts}"
        // keys, so they're excluded from read sync, dedupe and reactions.
        const val CONTENT_CALL = "call"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS conversations (
                jid             TEXT PRIMARY KEY,
                name            TEXT,
                is_group        INTEGER NOT NULL DEFAULT 0,
                last_message_id TEXT,
                last_timestamp  INTEGER NOT NULL DEFAULT 0,
                unread_count    INTEGER NOT NULL DEFAULT 0,
                is_muted        INTEGER NOT NULL DEFAULT 0,
                avatar_url      TEXT,
                updated_at      INTEGER NOT NULL DEFAULT 0,
                master_key      BLOB,
                group_revision  INTEGER NOT NULL DEFAULT 0
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS messages (
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
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_conv_ts ON messages(conversation_jid, timestamp DESC)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS reactions (
                message_id  TEXT NOT NULL,
                sender_jid  TEXT NOT NULL,
                emoji       TEXT NOT NULL,
                timestamp   INTEGER NOT NULL,
                PRIMARY KEY (message_id, sender_jid)
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS participants (
                conversation_jid TEXT NOT NULL,
                jid              TEXT NOT NULL,
                display_name     TEXT,
                role             TEXT DEFAULT 'member',
                PRIMARY KEY (conversation_jid, jid)
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS contacts (
                jid          TEXT PRIMARY KEY,
                name         TEXT,
                phone_number TEXT,
                profile_key  BLOB,
                profile_name TEXT,
                profile_fetched_at INTEGER NOT NULL DEFAULT 0,
                updated_at   INTEGER NOT NULL DEFAULT 0
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Add columns for Signal profile name resolution
            for (col in listOf(
                "phone_number TEXT",
                "profile_key BLOB",
                "profile_name TEXT",
                "profile_fetched_at INTEGER NOT NULL DEFAULT 0",
            )) {
                try {
                    db.execSQL("ALTER TABLE contacts ADD COLUMN $col")
                } catch (_: Exception) {
                    // Column may already exist on partially-upgraded databases
                }
            }
        }
        if (oldVersion < 3) {
            // GroupV2 metadata on the conversation row: master_key is the
            // 32-byte secret used to fetch/decrypt the group state from the
            // server; group_revision is the last seen revision so we know
            // when to re-fetch.
            for (col in listOf(
                "master_key BLOB",
                "group_revision INTEGER NOT NULL DEFAULT 0",
            )) {
                try {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN $col")
                } catch (_: Exception) {
                    // Column may already exist on partially-upgraded databases
                }
            }
        }
        if (oldVersion < 4) {
            markLocallySeenRows(db)
        }
    }

    /**
     * v4 split "seen in Photon" from "read-synced". Before, opening a chat
     * only zeroed unread_count and rows stayed 'received' until the read sync
     * succeeded, so a later primary read sync decremented the count that
     * belonged to newer messages. Each conversation's newest unread_count
     * 'received' rows are still unread; anything older was already seen.
     */
    private fun markLocallySeenRows(db: SQLiteDatabase) {
        val unreadByConversation = mutableMapOf<String, Int>()
        db.rawQuery(
            "SELECT DISTINCT m.conversation_jid, COALESCE(c.unread_count, 0) FROM messages m " +
                "LEFT JOIN conversations c ON c.jid = m.conversation_jid " +
                "WHERE m.is_from_me = 0 AND m.status = ?",
            arrayOf(STATUS_UNREAD),
        ).use { c ->
            while (c.moveToNext()) unreadByConversation[c.getString(0)] = c.getInt(1)
        }
        for ((jid, unread) in unreadByConversation) {
            db.execSQL(
                """
                UPDATE messages SET status = ?
                 WHERE id IN (
                    SELECT id FROM messages
                     WHERE conversation_jid = ? AND is_from_me = 0 AND status = ?
                     ORDER BY timestamp DESC, rowid DESC
                     LIMIT -1 OFFSET ?
                 )
                """.trimIndent(),
                arrayOf<Any?>(STATUS_SEEN, jid, STATUS_UNREAD, unread.coerceAtLeast(0)),
            )
        }
    }

    // ── Change notifications ──

    // Emits after every write so SignalRepository can re-query instead of
    // polling. The receiver and sender both go through this class for all
    // writes, so this is the single chokepoint. Conflated by the consumer —
    // tryEmit dropping a signal during a burst is fine.
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: SharedFlow<Unit> = _changes

    internal fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    /**
     * Close the helper and delete the database files (Signal reset). Holding
     * the helper's monitor — the one getWritableDatabase/getReadableDatabase
     * take — stops a concurrent reader reopening the old file between the
     * close and the delete. The next access creates a fresh, empty database.
     */
    fun closeAndDelete(context: Context) {
        synchronized(this) {
            close()
            context.deleteDatabase(DATABASE_NAME)
        }
        notifyChanged()
    }

    // ── Row types returned by the extension files ──

    data class GroupMeta(val masterKey: ByteArray, val revision: Int)

    data class Contact(
        val jid: String,
        val name: String?,
        val phone: String?,
        val profileKey: ByteArray?,
        val profileName: String?,
        val profileFetchedAt: Long,
    )

    /**
     * Outcome of applying one read-sync entry. [matched] is false when no
     * incoming row with that key exists yet (the read raced ahead of its
     * message). [clearedConversations] lists conversations whose unread count
     * this read brought to zero, i.e. whose notifications can go.
     */
    data class ReadSyncResult(val matched: Boolean, val clearedConversations: List<String>)

    /**
     * A delete-for-everyone took an unread message out of [conversationJid].
     * [unreadCount] is what is still unread there; [newerUnread] says whether
     * an unread message arrived after the deleted one, so that a notification
     * showing the newest message doesn't show the deleted text.
     */
    data class RemoteDeleteChange(val conversationJid: String, val unreadCount: Int, val newerUnread: Boolean)

    /** Read state of a call row around an update; see [upsertCallRow]. */
    data class CallRowChange(
        val conversationJid: String,
        val existed: Boolean,
        val wasUnread: Boolean,
        val isUnread: Boolean,
        val unreadCount: Int,
    )

    // ── Helpers for the extension files, on the caller's database handle ──

    internal fun rowExists(db: SQLiteDatabase, id: String): Boolean =
        db.rawQuery("SELECT 1 FROM messages WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

    internal fun prefixExists(db: SQLiteDatabase, prefix: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM messages WHERE id >= ? AND id < ? LIMIT 1",
            arrayOf("${prefix}_", "${prefix}_\uFFFF"),
        ).use { it.moveToFirst() }

    /** A late or redelivered message must not move the conversation back in time. */
    internal fun setLastMessageIfNewer(db: SQLiteDatabase, jid: String, messageId: String, timestamp: Long) {
        db.execSQL(
            "UPDATE conversations SET last_message_id = ?, last_timestamp = ?, updated_at = ? " +
                "WHERE jid = ? AND last_timestamp <= ?",
            arrayOf<Any?>(messageId, timestamp, System.currentTimeMillis(), jid, timestamp),
        )
    }

    /**
     * Subtract per-conversation counts from unread_count (never below zero).
     * Returns the conversations whose count reached zero, so callers can
     * clear their notifications. Must run inside the caller's transaction.
     */
    internal fun decrementUnread(db: SQLiteDatabase, counts: Map<String, Int>): List<String> {
        val cleared = mutableListOf<String>()
        for ((jid, count) in counts) {
            if (count <= 0) continue
            db.execSQL(
                "UPDATE conversations SET unread_count = MAX(0, unread_count - ?) WHERE jid = ?",
                arrayOf<Any?>(count, jid),
            )
            db.rawQuery("SELECT unread_count FROM conversations WHERE jid = ?", arrayOf(jid)).use { c ->
                if (!c.moveToFirst() || c.getInt(0) == 0) cleared.add(jid)
            }
        }
        return cleared
    }

    internal fun adjustUnread(db: SQLiteDatabase, jid: String, delta: Int) {
        db.execSQL(
            "UPDATE conversations SET unread_count = MAX(0, unread_count + ?) WHERE jid = ?",
            arrayOf<Any?>(delta, jid),
        )
    }

    internal fun unreadCountOf(db: SQLiteDatabase, jid: String): Int =
        db.rawQuery("SELECT unread_count FROM conversations WHERE jid = ?", arrayOf(jid))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
}
