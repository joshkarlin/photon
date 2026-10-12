package app.photon.signal.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import app.photon.data.model.MessageStatus
import app.photon.signal.documentName
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.whispersystems.signalservice.internal.push.AttachmentPointer

/**
 * Photon's Signal message store: schema, migrations and change
 * notifications. The queries are extension functions grouped by concern in
 * this package: SignalConversations, SignalMessages, SignalReadState,
 * SignalContacts, SignalReactions, SignalCallRows, SignalRetention,
 * SignalStorageMutes, SignalTimers and SignalEdits.
 */
class SignalMessageDatabase(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, 8,
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

        private const val TAG = "SignalRepository"
    }

    /** The conversations table as it is now, under [name] (a rebuild makes it under another first). */
    private fun createConversationsTable(db: SQLiteDatabase, name: String = "conversations") {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $name (
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
                group_revision  INTEGER NOT NULL DEFAULT 0,
                muted_until     INTEGER NOT NULL DEFAULT 0,
                archived        INTEGER NOT NULL DEFAULT 0,
                expire_timer    INTEGER,
                expire_timer_version INTEGER,
                timer_revision  INTEGER NOT NULL DEFAULT -1
            )
        """)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createConversationsTable(db)
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
                expires_in      INTEGER NOT NULL DEFAULT 0,
                expires_at      INTEGER NOT NULL DEFAULT 0,
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
                updated_at   INTEGER NOT NULL DEFAULT 0,
                expire_timer INTEGER,
                expire_timer_version INTEGER
            )
        """)
        createStorageTables(db)
        createV7Tables(db)
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
        if (oldVersion < 5) {
            clearStoredDisplayNames(db)
        }
        if (oldVersion < 6) {
            // Mutes read from the primary's storage service. Every chat starts
            // unmuted (Signal never wrote is_muted); the first storage read
            // after the upgrade fills them in.
            try {
                db.execSQL("ALTER TABLE conversations ADD COLUMN muted_until INTEGER NOT NULL DEFAULT 0")
            } catch (_: Exception) {
                // Column may already exist on partially-upgraded databases
            }
            createStorageTables(db)
        }
        if (oldVersion < 7) {
            upgradeToV7(db)
        }
        if (oldVersion < 8) {
            upgradeToV8(db)
        }
    }

    /**
     * v7: disappearing messages, archived chats, and messages that used to
     * be dropped or shown without their names.
     *
     *  - conversations: `archived` (from the primary's storage service),
     *    `expire_timer` / `expire_timer_version` (the chat's timer, 0 = off;
     *    NULL until Photon learns it, so every chat starts unknown), and
     *    `timer_revision` (for a group, the group revision its timer was
     *    read at; -1 = never, so every group's state is fetched once).
     *  - messages: `expires_in` (seconds) and `expires_at` (epoch ms when the
     *    message goes; 0 = its clock hasn't started).
     *  - contacts: the timer the primary's contact sync gave each person, for
     *    a chat Photon has no row for yet.
     *  - storage_records: each record's `archived`, and the account record's
     *    "keep muted chats archived". Existing installs read every record
     *    again: until now only mutes were kept, and a read only fetches
     *    records it hasn't seen.
     *  - message_aliases: an edit's own timestamp, for later edits, reactions
     *    and deletes that name it.
     *  - Received documents get their file name from the stored pointer.
     */
    private fun upgradeToV7(db: SQLiteDatabase) {
        for ((table, col) in listOf(
            "conversations" to "archived INTEGER NOT NULL DEFAULT 0",
            "conversations" to "expire_timer INTEGER",
            "conversations" to "expire_timer_version INTEGER",
            "conversations" to "timer_revision INTEGER NOT NULL DEFAULT -1",
            "messages" to "expires_in INTEGER NOT NULL DEFAULT 0",
            "messages" to "expires_at INTEGER NOT NULL DEFAULT 0",
            "contacts" to "expire_timer INTEGER",
            "contacts" to "expire_timer_version INTEGER",
            "storage_records" to "archived INTEGER NOT NULL DEFAULT 0",
            "storage_records" to "keep_muted_archived INTEGER NOT NULL DEFAULT 0",
        )) {
            try {
                db.execSQL("ALTER TABLE $table ADD COLUMN $col")
            } catch (_: Exception) {
                // Column may already exist on partially-upgraded databases
            }
        }
        createV7Tables(db)
        db.execSQL("DELETE FROM storage_records")
        db.execSQL("DELETE FROM storage_manifest")
        backfillDocumentNames(db)
    }

    /**
     * v8: a chat's timer is unknown (NULL) until Photon learns it. The first
     * v7 (never released) stored every chat as off at version 1, which a
     * send then carried and turned a version-1 timer off for everyone. A
     * database made by it gets the columns again, nullable and empty: every
     * DM's timer is learned again (from its next message, transcript or
     * contact sync) and every group's state is fetched again. A database
     * that came through the current v7 already has them nullable.
     *
     * SQLite can't drop NOT NULL from a column in place (and DROP COLUMN
     * isn't everywhere), so the table is rebuilt as SQLite documents it:
     * a new table, every other column copied, the old one dropped and the
     * new one renamed. Nothing else refers to it but the messages' foreign
     * key, which names it and so holds.
     */
    private fun upgradeToV8(db: SQLiteDatabase) {
        val columns = db.rawQuery("PRAGMA table_info(conversations)", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(1), c.getInt(c.getColumnIndexOrThrow("notnull")) == 1) }
        }
        if (columns["expire_timer"] != true && columns["expire_timer_version"] != true) return
        createConversationsTable(db, "conversations_v8")
        val kept = db.rawQuery("PRAGMA table_info(conversations_v8)", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1)) }
        }.filter { it in columns && it != "expire_timer" && it != "expire_timer_version" }.joinToString(", ")
        db.execSQL("INSERT INTO conversations_v8 ($kept) SELECT $kept FROM conversations")
        db.execSQL("DROP TABLE conversations")
        db.execSQL("ALTER TABLE conversations_v8 RENAME TO conversations")
        db.execSQL("UPDATE conversations SET timer_revision = -1")
        Log.i(TAG, "Signal chat timers reset to unknown")
    }

    private fun createV7Tables(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_expires_at ON messages(expires_at) WHERE expires_at > 0")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS message_aliases (
                alias     TEXT PRIMARY KEY,
                target_id TEXT NOT NULL
            )
        """)
    }

    /**
     * Received documents were stored without their names, though each row
     * kept its AttachmentPointer, which carries it. Fill in the blanks; a
     * document that came with a caption keeps it.
     */
    private fun backfillDocumentNames(db: SQLiteDatabase) {
        val names = mutableListOf<Pair<String, String>>()
        db.rawQuery(
            "SELECT id, raw_proto FROM messages WHERE content_type = 'document' " +
                "AND raw_proto IS NOT NULL AND (text_body IS NULL OR TRIM(text_body) = '')",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val name = try {
                    documentName(AttachmentPointer.ADAPTER.decode(c.getBlob(1)).fileName)
                } catch (_: Exception) {
                    null
                }
                if (name != null) names += c.getString(0) to name
            }
        }
        for ((id, name) in names) {
            db.execSQL("UPDATE messages SET text_body = ? WHERE id = ?", arrayOf(name, id))
        }
        if (names.isNotEmpty()) Log.i(TAG, "Named ${names.size} received documents from their pointers")
    }

    /**
     * What Photon has read from the storage service (SignalStorageMutes):
     * each contact, GroupV2 and account record it has seen, by storage id,
     * with the conversation it maps to, its mute and whether it's archived
     * (the account record: Note to Self's, plus "keep muted chats
     * archived"); and the manifest version those records belong to (one
     * row, id 1).
     */
    private fun createStorageTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS storage_records (
                storage_id  TEXT PRIMARY KEY,
                jid         TEXT,
                muted_until INTEGER NOT NULL DEFAULT 0,
                archived    INTEGER NOT NULL DEFAULT 0,
                keep_muted_archived INTEGER NOT NULL DEFAULT 0
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_storage_records_jid ON storage_records(jid)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS storage_manifest (
                id      INTEGER PRIMARY KEY CHECK (id = 1),
                version INTEGER NOT NULL
            )
        """)
    }

    /**
     * v5 resolves names when they're read (SignalNames), from the contacts
     * table, so a stored name can only go stale: older builds wrote whatever
     * they resolved at the time (an LP3 name, a profile name, a number) into
     * DM rows and member rows, and a profile fetch could overwrite the LP3
     * name. Groups keep their subject; the "Signal group" placeholder (and a
     * raw id) was never one, and a cleared name is re-fetched on connect.
     */
    private fun clearStoredDisplayNames(db: SQLiteDatabase) {
        db.execSQL("UPDATE conversations SET name = NULL WHERE is_group = 0")
        db.execSQL("UPDATE participants SET display_name = NULL")
        db.execSQL(
            "UPDATE conversations SET name = NULL " +
                "WHERE is_group = 1 AND (TRIM(name) = '' OR name = jid OR name = 'Signal group')",
        )
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

    // Bumped whenever a message's disappearing clock starts, so the expiry
    // sweep (SignalSession) can wake for a timer shorter than its sleep. A
    // StateFlow, so a bump between a sweep and its wait isn't lost.
    private val _expiryScheduled = MutableStateFlow(0L)
    val expiryScheduled: StateFlow<Long> = _expiryScheduled

    internal fun notifyExpiryScheduled() {
        _expiryScheduled.update { it + 1 }
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

/**
 * A Signal row's [MessageStatus]: incoming rows store their read state (see
 * [SignalMessageDatabase.STATUS_UNREAD] and the rest), outgoing rows their
 * delivery state.
 */
internal fun signalStatus(stored: String, isFromMe: Boolean): MessageStatus = when {
    isFromMe -> MessageStatus.outgoing(stored)
    stored == SignalMessageDatabase.STATUS_SEEN -> MessageStatus.SEEN
    stored == SignalMessageDatabase.STATUS_READ -> MessageStatus.READ
    stored == SignalMessageDatabase.STATUS_DELETED -> MessageStatus.DELETED
    else -> MessageStatus.UNREAD
}
