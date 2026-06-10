package app.photon.signal.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.photon.data.db.toConversation
import app.photon.data.db.toConversations
import app.photon.data.db.toMessage
import app.photon.data.db.toMessages
import app.photon.data.db.toReaction
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.Reaction
import app.photon.data.model.Participant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class SignalMessageDatabase(context: Context) : SQLiteOpenHelper(
    context, "signal_messages.db", null, 3,
) {
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
    }

    // ── Change notifications ──

    // Emits after every write so SignalRepository can re-query instead of
    // polling. The receiver and sender both go through this class for all
    // writes, so this is the single chokepoint. Conflated by the consumer —
    // tryEmit dropping a signal during a burst is fine.
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: SharedFlow<Unit> = _changes

    private fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    // ── Write methods ──

    fun upsertConversation(
        jid: String,
        name: String?,
        isGroup: Boolean,
        lastMessageId: String? = null,
        lastTimestamp: Long = 0,
    ) {
        // Hand-rolled UPSERT so that callers who only know name/jid (e.g. the
        // contacts-sync ingest) don't clobber the existing last_message_id or
        // last_timestamp on a conversation that already has message history.
        // INSERT plants defaults on new rows; UPDATE only writes the fields
        // the caller actually supplied.
        writableDatabase.execSQL(
            """
            INSERT INTO conversations (jid, name, is_group, last_message_id, last_timestamp, updated_at)
              VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(jid) DO UPDATE SET
              name           = COALESCE(excluded.name, conversations.name),
              is_group       = excluded.is_group,
              last_message_id = COALESCE(NULLIF(excluded.last_message_id, ''), conversations.last_message_id),
              last_timestamp  = CASE WHEN excluded.last_timestamp > 0
                                     THEN excluded.last_timestamp
                                     ELSE conversations.last_timestamp END,
              updated_at      = excluded.updated_at
            """,
            arrayOf<Any?>(
                jid,
                name,
                if (isGroup) 1 else 0,
                lastMessageId ?: "",
                lastTimestamp,
                System.currentTimeMillis(),
            ),
        )
        notifyChanged()
    }

    fun updateConversationLastMessage(jid: String, messageId: String, timestamp: Long) {
        writableDatabase.execSQL(
            "UPDATE conversations SET last_message_id = ?, last_timestamp = ?, updated_at = ? WHERE jid = ?",
            arrayOf(messageId, timestamp, System.currentTimeMillis(), jid),
        )
        notifyChanged()
    }

    fun incrementUnread(jid: String) {
        writableDatabase.execSQL(
            "UPDATE conversations SET unread_count = unread_count + 1 WHERE jid = ?",
            arrayOf(jid),
        )
        notifyChanged()
    }

    fun resetUnread(jid: String) {
        writableDatabase.execSQL(
            "UPDATE conversations SET unread_count = 0 WHERE jid = ?",
            arrayOf(jid),
        )
        notifyChanged()
    }

    /**
     * Persist the GroupV2 metadata on a conversation row. We need the
     * master_key to (re)fetch group state from the server; the revision
     * lets us skip refetches when nothing has changed since we last looked.
     * Safe to call repeatedly with the same values — no-op via UPDATE.
     */
    fun updateGroupMeta(jid: String, masterKey: ByteArray, revision: Int) {
        writableDatabase.execSQL(
            "UPDATE conversations SET master_key = ?, group_revision = ? WHERE jid = ?",
            arrayOf<Any?>(masterKey, revision, jid),
        )
        notifyChanged()
    }

    data class GroupMeta(val masterKey: ByteArray, val revision: Int)

    fun getGroupMeta(jid: String): GroupMeta? {
        readableDatabase.rawQuery(
            "SELECT master_key, group_revision FROM conversations WHERE jid = ?",
            arrayOf(jid),
        ).use { c ->
            if (!c.moveToFirst()) return null
            val mk = c.getBlob(0) ?: return null
            val rev = c.getInt(1)
            return GroupMeta(mk, rev)
        }
    }

    fun insertMessage(
        id: String,
        conversationJid: String,
        senderJid: String,
        timestamp: Long,
        contentType: String,
        textBody: String?,
        mediaUrl: String? = null,
        mediaMime: String? = null,
        mediaSize: Long? = null,
        replyToId: String? = null,
        isFromMe: Boolean = false,
        status: String = "received",
        rawProto: ByteArray? = null,
    ) {
        writableDatabase.insertWithOnConflict(
            "messages", null,
            ContentValues().apply {
                put("id", id)
                put("conversation_jid", conversationJid)
                put("sender_jid", senderJid)
                put("timestamp", timestamp)
                put("content_type", contentType)
                put("text_body", textBody)
                put("media_url", mediaUrl)
                put("media_mime", mediaMime)
                if (mediaSize != null) put("media_size", mediaSize)
                put("reply_to_id", replyToId)
                put("is_from_me", if (isFromMe) 1 else 0)
                put("status", status)
                // For incoming media: the serialized AttachmentPointer proto,
                // so the attachment can be downloaded on demand later.
                if (rawProto != null) put("raw_proto", rawProto)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        notifyChanged()
    }

    fun getMessageRawProto(id: String): ByteArray? {
        readableDatabase.rawQuery(
            "SELECT raw_proto FROM messages WHERE id = ?", arrayOf(id),
        ).use { c ->
            if (!c.moveToFirst() || c.isNull(0)) return null
            return c.getBlob(0)
        }
    }

    fun updateMessageStatus(id: String, status: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET status = ? WHERE id = ?",
            arrayOf(status, id),
        )
        notifyChanged()
    }

    fun updateMediaUrl(id: String, url: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET media_url = ? WHERE id = ?",
            arrayOf(url, id),
        )
        notifyChanged()
    }

    /**
     * Clear media_url on whatever message references a pruned file, so the
     * UI falls back to the download-on-tap path instead of a dead file.
     */
    fun clearMediaUrlByPath(path: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET media_url = NULL WHERE media_url = ?",
            arrayOf(path),
        )
        notifyChanged()
    }

    fun upsertReaction(messageId: String, senderJid: String, emoji: String, timestamp: Long) {
        writableDatabase.insertWithOnConflict(
            "reactions", null,
            ContentValues().apply {
                put("message_id", messageId)
                put("sender_jid", senderJid)
                put("emoji", emoji)
                put("timestamp", timestamp)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        notifyChanged()
    }

    fun upsertParticipant(conversationJid: String, jid: String, displayName: String?, role: String = "member") {
        // Preserve any previously known display_name when the caller passes
        // null/blank — repeated upserts with sparse data shouldn't clobber.
        writableDatabase.execSQL(
            """
            INSERT INTO participants (conversation_jid, jid, display_name, role)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(conversation_jid, jid) DO UPDATE SET
                display_name = COALESCE(NULLIF(excluded.display_name, ''), participants.display_name),
                role = excluded.role
            """,
            arrayOf<Any?>(conversationJid, jid, displayName, role),
        )
        notifyChanged()
    }

    fun getParticipants(conversationJid: String): List<Participant> {
        val out = mutableListOf<Participant>()
        readableDatabase.rawQuery(
            "SELECT jid, display_name, role FROM participants WHERE conversation_jid = ?",
            arrayOf(conversationJid),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Participant(
                        conversationJid = conversationJid,
                        jid = c.getString(0),
                        displayName = c.getString(1),
                        role = c.getString(2) ?: "member",
                    )
                )
            }
        }
        return out
    }

    fun updateContactPhone(jid: String, phone: String) {
        writableDatabase.execSQL(
            """
            INSERT INTO contacts (jid, phone_number, updated_at) VALUES (?, ?, ?)
            ON CONFLICT(jid) DO UPDATE SET phone_number = excluded.phone_number
            """,
            arrayOf<Any?>(jid, phone, System.currentTimeMillis()),
        )
        notifyChanged()
    }

    fun updateContactProfileKey(jid: String, profileKey: ByteArray) {
        writableDatabase.execSQL(
            """
            INSERT INTO contacts (jid, profile_key, updated_at) VALUES (?, ?, ?)
            ON CONFLICT(jid) DO UPDATE SET profile_key = excluded.profile_key
            """,
            arrayOf<Any?>(jid, profileKey, System.currentTimeMillis()),
        )
    }

    fun updateContactProfileName(jid: String, profileName: String?) {
        writableDatabase.execSQL(
            """
            INSERT INTO contacts (jid, profile_name, profile_fetched_at, updated_at) VALUES (?, ?, ?, ?)
            ON CONFLICT(jid) DO UPDATE SET
                profile_name = excluded.profile_name,
                profile_fetched_at = excluded.profile_fetched_at
            """,
            arrayOf<Any?>(jid, profileName, System.currentTimeMillis(), System.currentTimeMillis()),
        )
        notifyChanged()
    }

    data class Contact(
        val jid: String,
        val name: String?,
        val phone: String?,
        val profileKey: ByteArray?,
        val profileName: String?,
        val profileFetchedAt: Long,
    )

    fun getContact(jid: String): Contact? {
        return readableDatabase.rawQuery(
            "SELECT jid, name, phone_number, profile_key, profile_name, profile_fetched_at FROM contacts WHERE jid = ?",
            arrayOf(jid),
        ).use { c ->
            if (c.moveToFirst()) {
                Contact(
                    jid = c.getString(0),
                    name = if (c.isNull(1)) null else c.getString(1),
                    phone = if (c.isNull(2)) null else c.getString(2),
                    profileKey = if (c.isNull(3)) null else c.getBlob(3),
                    profileName = if (c.isNull(4)) null else c.getString(4),
                    profileFetchedAt = c.getLong(5),
                )
            } else null
        }
    }

    /**
     * Display-name policy for a group member, shared by receiver and
     * sender so the participants table stays consistent: Signal profile
     * name → local contact (when the caller supplies a phone resolver)
     * → bare phone → ACI prefix as last resort.
     */
    fun memberDisplayName(aci: String, contactResolver: ((String) -> String?)? = null): String {
        val contact = getContact(aci)
        return contact?.profileName?.takeIf { it.isNotBlank() }
            ?: contact?.phone?.let { phone -> contactResolver?.invoke(phone) ?: phone }
            ?: aci.take(8)
    }

    /**
     * All contacts whose profile we know how to fetch (have profile_key)
     * but for which the encrypted name hasn't been decoded yet. Used by the
     * receiver to retry failed profile fetches on each WS (re)connect.
     */
    fun getContactsWithProfileKeyButNoName(): List<Contact> {
        return readableDatabase.rawQuery(
            "SELECT jid, name, phone_number, profile_key, profile_name, profile_fetched_at " +
                "FROM contacts WHERE profile_key IS NOT NULL " +
                "AND (profile_name IS NULL OR profile_name = '')",
            null,
        ).use { c ->
            val out = mutableListOf<Contact>()
            while (c.moveToNext()) {
                out.add(
                    Contact(
                        jid = c.getString(0),
                        name = if (c.isNull(1)) null else c.getString(1),
                        phone = if (c.isNull(2)) null else c.getString(2),
                        profileKey = if (c.isNull(3)) null else c.getBlob(3),
                        profileName = if (c.isNull(4)) null else c.getString(4),
                        profileFetchedAt = c.getLong(5),
                    ),
                )
            }
            out
        }
    }

    // ── Read methods (same as PhotonDatabase) ──

    fun getConversations(): List<Conversation> {
        return readableDatabase.rawQuery(
            "SELECT * FROM conversations ORDER BY last_timestamp DESC", null,
        ).use { it.toConversations() }
    }

    fun getConversation(jid: String): Conversation? {
        return readableDatabase.rawQuery(
            "SELECT * FROM conversations WHERE jid = ?", arrayOf(jid),
        ).use { if (it.moveToFirst()) it.toConversation() else null }
    }

    fun getMessages(conversationJid: String, limit: Int = 50): List<Message> {
        return readableDatabase.rawQuery(
            // Tiebreak on _rowid_ so two messages sent in the same wall-clock
            // second still render in insertion order. Without this, sending
            // two messages back-to-back occasionally swaps them in the chat
            // (storage is in seconds; the wire timestamp is sub-second).
            "SELECT * FROM messages WHERE conversation_jid = ? ORDER BY timestamp DESC, _rowid_ DESC LIMIT ?",
            arrayOf(conversationJid, limit.toString()),
        ).use { it.toMessages() }
    }

    /**
     * Wipe `profile_name` on every contact so the Signal profile fetcher
     * will refetch on next message activity. Used as a one-shot migration
     * when the resolution policy changes — clears stale names sourced from
     * the primary device's address book.
     */
    fun clearAllStoredProfileNames() {
        writableDatabase.execSQL(
            "UPDATE contacts SET profile_name = NULL, profile_fetched_at = 0",
        )
        notifyChanged()
    }

    /**
     * For each existing conversation, look at the row's stored phone (from
     * the contacts table) and reset `name` to whatever the supplied resolver
     * returns. Used to re-derive conversation titles from local contacts
     * when the resolution policy changes. Conversations with no phone are
     * left alone.
     */
    fun reresolveConversationNames(resolveByPhone: (String) -> String?): Int {
        var updated = 0
        readableDatabase.rawQuery(
            "SELECT c.jid, c.name, ct.phone_number, ct.profile_name FROM conversations c " +
                "LEFT JOIN contacts ct ON ct.jid = c.jid",
            null,
        ).use { cur ->
            while (cur.moveToNext()) {
                val jid = cur.getString(0)
                val currentName = if (cur.isNull(1)) null else cur.getString(1)
                val phone = if (cur.isNull(2)) null else cur.getString(2)
                val profileName = if (cur.isNull(3)) null else cur.getString(3)
                // Resolution priority: local LP3 contact → Signal profile
                // name (when the fetcher could decrypt one) → phone number.
                val resolved = phone?.let { resolveByPhone(it) }
                    ?: profileName?.takeIf { it.isNotBlank() }
                    ?: phone
                if (resolved != currentName) {
                    writableDatabase.execSQL(
                        "UPDATE conversations SET name = ? WHERE jid = ?",
                        arrayOf<Any?>(resolved, jid),
                    )
                    updated++
                }
            }
        }
        if (updated > 0) notifyChanged()
        return updated
    }

    /**
     * Repair last_timestamp on every conversation whose row is desynced from
     * the messages table — sets it to MAX(messages.timestamp) where the
     * conversation has any messages stored. Idempotent; safe to run on every
     * startup. Needed because an earlier bug in upsertConversation
     * (CONFLICT_REPLACE) used to zero-out last_timestamp whenever contacts
     * sync ran, hiding active chats from the recent-chats sort.
     */
    fun repairConversationTimestamps() {
        writableDatabase.execSQL(
            """
            UPDATE conversations
            SET last_timestamp = COALESCE(
                (SELECT MAX(timestamp) FROM messages WHERE messages.conversation_jid = conversations.jid),
                last_timestamp
            )
            WHERE EXISTS (SELECT 1 FROM messages WHERE messages.conversation_jid = conversations.jid)
            """,
        )
        notifyChanged()
    }

    /**
     * JIDs of DM conversations that have at least one message stored.
     * Group JIDs (is_group = 1) are excluded — callers who use this list
     * to ping contacts would otherwise try to parse a group identifier as
     * an ACI and fail loudly.
     */
    fun getJidsWithMessages(): List<String> {
        return readableDatabase.rawQuery(
            """
            SELECT DISTINCT m.conversation_jid
              FROM messages m
              LEFT JOIN conversations c ON c.jid = m.conversation_jid
              WHERE COALESCE(c.is_group, 0) = 0
            """,
            null,
        ).use { c ->
            val out = mutableListOf<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }
    }

    fun getMessage(id: String): Message? {
        return readableDatabase.rawQuery(
            "SELECT * FROM messages WHERE id = ?", arrayOf(id),
        ).use { if (it.moveToFirst()) it.toMessage() else null }
    }

    /**
     * Find a message by its `"${authorAci}_${timestamp}"` stable prefix. Signal message
     * IDs get a random suffix when stored locally so reply quotes (which only know the
     * original sent timestamp + author) can't look up by exact ID.
     */
    fun findMessageByPrefix(prefix: String): Message? {
        // Range scan instead of LIKE — LIKE is case-insensitive in SQLite so
        // it can't use the PK index. IDs are ASCII "{aci}_{tsMs}_{rand}", so
        // ["prefix_", "prefix_\uFFFF") covers every suffix.
        return readableDatabase.rawQuery(
            "SELECT * FROM messages WHERE id >= ? AND id < ? LIMIT 1",
            arrayOf("${prefix}_", "${prefix}_\uFFFF"),
        ).use { if (it.moveToFirst()) it.toMessage() else null }
    }

    /**
     * Status update for our own messages matched by the stable
     * "{aci}_{tsMs}" prefix — delivery/read receipts only carry the original
     * sent timestamp, not the random suffix we add for PK uniqueness. Same
     * index-friendly range form as findMessageByPrefix. Never regresses a
     * 'read' back to 'delivered'.
     */
    fun updateStatusByIdPrefix(prefix: String, status: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET status = ? WHERE id >= ? AND id < ? AND is_from_me = 1 AND status != 'read'",
            arrayOf(status, "${prefix}_", "${prefix}_\uFFFF"),
        )
        notifyChanged()
    }

    fun getReactions(messageIds: List<String>): Map<String, List<Reaction>> {
        if (messageIds.isEmpty()) return emptyMap()
        val placeholders = messageIds.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT * FROM reactions WHERE message_id IN ($placeholders)",
            messageIds.toTypedArray(),
        ).use { c ->
            val result = mutableMapOf<String, MutableList<Reaction>>()
            while (c.moveToNext()) {
                val r = c.toReaction()
                result.getOrPut(r.messageId) { mutableListOf() }.add(r)
            }
            result
        }
    }
}
