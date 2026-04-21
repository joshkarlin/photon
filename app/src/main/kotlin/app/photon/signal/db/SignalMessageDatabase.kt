package app.photon.signal.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.Reaction
import app.photon.data.model.Participant

class SignalMessageDatabase(context: Context) : SQLiteOpenHelper(
    context, "signal_messages.db", null, 2,
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
                updated_at      INTEGER NOT NULL DEFAULT 0
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
    }

    // ── Write methods ──

    fun upsertConversation(
        jid: String,
        name: String?,
        isGroup: Boolean,
        lastMessageId: String? = null,
        lastTimestamp: Long = 0,
    ) {
        writableDatabase.insertWithOnConflict(
            "conversations", null,
            ContentValues().apply {
                put("jid", jid)
                put("name", name)
                put("is_group", if (isGroup) 1 else 0)
                if (lastMessageId != null) put("last_message_id", lastMessageId)
                if (lastTimestamp > 0) put("last_timestamp", lastTimestamp)
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun updateConversationLastMessage(jid: String, messageId: String, timestamp: Long) {
        writableDatabase.execSQL(
            "UPDATE conversations SET last_message_id = ?, last_timestamp = ?, updated_at = ? WHERE jid = ?",
            arrayOf(messageId, timestamp, System.currentTimeMillis(), jid),
        )
    }

    fun incrementUnread(jid: String) {
        writableDatabase.execSQL(
            "UPDATE conversations SET unread_count = unread_count + 1 WHERE jid = ?",
            arrayOf(jid),
        )
    }

    fun resetUnread(jid: String) {
        writableDatabase.execSQL(
            "UPDATE conversations SET unread_count = 0 WHERE jid = ?",
            arrayOf(jid),
        )
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
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun updateMessageStatus(id: String, status: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET status = ? WHERE id = ?",
            arrayOf(status, id),
        )
    }

    fun updateMediaUrl(id: String, url: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET media_url = ? WHERE id = ?",
            arrayOf(url, id),
        )
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
    }

    fun upsertParticipant(conversationJid: String, jid: String, displayName: String?, role: String = "member") {
        writableDatabase.insertWithOnConflict(
            "participants", null,
            ContentValues().apply {
                put("conversation_jid", conversationJid)
                put("jid", jid)
                put("display_name", displayName)
                put("role", role)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun upsertContact(jid: String, name: String?) {
        // Preserve existing profile_key / phone_number on re-upsert.
        writableDatabase.execSQL(
            """
            INSERT INTO contacts (jid, name, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(jid) DO UPDATE SET
                name = COALESCE(excluded.name, contacts.name),
                updated_at = excluded.updated_at
            """,
            arrayOf<Any?>(jid, name, System.currentTimeMillis()),
        )
    }

    fun updateContactPhone(jid: String, phone: String) {
        writableDatabase.execSQL(
            """
            INSERT INTO contacts (jid, phone_number, updated_at) VALUES (?, ?, ?)
            ON CONFLICT(jid) DO UPDATE SET phone_number = excluded.phone_number
            """,
            arrayOf<Any?>(jid, phone, System.currentTimeMillis()),
        )
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

    fun getContactName(jid: String): String? {
        val contact = getContact(jid) ?: return null
        return contact.profileName?.takeIf { it.isNotBlank() }
            ?: contact.name?.takeIf { it.isNotBlank() }
            ?: contact.phone
    }

    // ── Read methods (same as PhotonDatabase) ──

    fun getConversations(): List<Conversation> {
        return readableDatabase.rawQuery("""
            SELECT c.*, m.text_body as last_preview, m.content_type as last_type
            FROM conversations c
            LEFT JOIN messages m ON c.last_message_id = m.id
            ORDER BY c.last_timestamp DESC
        """, null).use { it.toConversations() }
    }

    fun getConversation(jid: String): Conversation? {
        return readableDatabase.rawQuery(
            "SELECT * FROM conversations WHERE jid = ?", arrayOf(jid),
        ).use { if (it.moveToFirst()) it.toConversation() else null }
    }

    fun getMessages(conversationJid: String, limit: Int = 50): List<Message> {
        return readableDatabase.rawQuery(
            "SELECT * FROM messages WHERE conversation_jid = ? ORDER BY timestamp DESC LIMIT ?",
            arrayOf(conversationJid, limit.toString()),
        ).use { it.toMessages() }
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
        return readableDatabase.rawQuery(
            "SELECT * FROM messages WHERE id LIKE ? LIMIT 1",
            arrayOf("${prefix}_%"),
        ).use { if (it.moveToFirst()) it.toMessage() else null }
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

    // ── Cursor mappers (identical to PhotonDatabase) ──

    private fun Cursor.toConversations(): List<Conversation> {
        val list = mutableListOf<Conversation>()
        while (moveToNext()) list.add(toConversation())
        return list
    }

    private fun Cursor.toConversation(): Conversation {
        return Conversation(
            jid = getString(getColumnIndexOrThrow("jid")),
            name = getStringOrNull("name"),
            isGroup = getInt(getColumnIndexOrThrow("is_group")) == 1,
            lastMessageId = getStringOrNull("last_message_id"),
            lastTimestamp = getLong(getColumnIndexOrThrow("last_timestamp")),
            unreadCount = getInt(getColumnIndexOrThrow("unread_count")),
            isMuted = getInt(getColumnIndexOrThrow("is_muted")) == 1,
            avatarUrl = getStringOrNull("avatar_url"),
            updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
            lastMessagePreview = getStringOrNull("last_preview"),
        )
    }

    private fun Cursor.toMessages(): List<Message> {
        val list = mutableListOf<Message>()
        while (moveToNext()) list.add(toMessage())
        return list
    }

    private fun Cursor.toMessage(): Message {
        return Message(
            id = getString(getColumnIndexOrThrow("id")),
            conversationJid = getString(getColumnIndexOrThrow("conversation_jid")),
            senderJid = getString(getColumnIndexOrThrow("sender_jid")),
            timestamp = getLong(getColumnIndexOrThrow("timestamp")),
            contentType = getString(getColumnIndexOrThrow("content_type")),
            textBody = getStringOrNull("text_body"),
            mediaUrl = getStringOrNull("media_url"),
            mediaMime = getStringOrNull("media_mime"),
            mediaSize = getLongOrNull("media_size"),
            thumbnailPath = getStringOrNull("thumbnail_path"),
            stickerPackId = getStringOrNull("sticker_pack_id"),
            replyToId = getStringOrNull("reply_to_id"),
            editVersion = getInt(getColumnIndexOrThrow("edit_version")),
            isFromMe = getInt(getColumnIndexOrThrow("is_from_me")) == 1,
            status = getString(getColumnIndexOrThrow("status")),
        )
    }

    private fun Cursor.toReaction(): Reaction {
        return Reaction(
            messageId = getString(getColumnIndexOrThrow("message_id")),
            senderJid = getString(getColumnIndexOrThrow("sender_jid")),
            emoji = getString(getColumnIndexOrThrow("emoji")),
            timestamp = getLong(getColumnIndexOrThrow("timestamp")),
        )
    }

    private fun Cursor.getStringOrNull(column: String): String? {
        val idx = getColumnIndex(column)
        return if (idx >= 0 && !isNull(idx)) getString(idx) else null
    }

    private fun Cursor.getLongOrNull(column: String): Long? {
        val idx = getColumnIndex(column)
        return if (idx >= 0 && !isNull(idx)) getLong(idx) else null
    }
}
