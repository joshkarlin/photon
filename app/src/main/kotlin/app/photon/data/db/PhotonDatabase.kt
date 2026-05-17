package app.photon.data.db

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.Reaction
import app.photon.data.model.Participant
import java.io.File

/**
 * Read-only SQLite access to the message database written by the Go bridge.
 * The Go bridge owns the schema and writes all data.
 */
class PhotonDatabase(context: Context) {
    private val dbPath = File(context.filesDir, "messages.db").absolutePath

    // Set to true the first time we successfully open the DB. Once set, we know
    // the file is supposed to exist; subsequent "missing" reads should be
    // treated as transient (e.g. file-based-encryption blocking access while
    // the phone is locked) rather than as "DB never created".
    @Volatile private var hasOpenedOnce: Boolean = false

    private fun open(): SQLiteDatabase? {
        val file = File(dbPath)
        if (!file.exists()) {
            if (hasOpenedOnce) {
                throw IllegalStateException("messages.db not accessible (FBE-locked?)")
            }
            return null
        }
        return SQLiteDatabase.openDatabase(
            dbPath, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
        ).also { hasOpenedOnce = true }
    }

    fun getConversations(): List<Conversation> {
        val db = open() ?: return emptyList()
        return db.use { d ->
            val cursor = d.rawQuery("""
                SELECT c.*, m.text_body as last_preview, m.content_type as last_type
                FROM conversations c
                LEFT JOIN messages m ON c.last_message_id = m.id
                ORDER BY c.last_timestamp DESC
            """, null)
            cursor.use { it.toConversations() }
        }
    }

    fun getConversation(jid: String): Conversation? {
        val db = open() ?: return null
        return db.use { d ->
            val cursor = d.rawQuery(
                "SELECT * FROM conversations WHERE jid = ?",
                arrayOf(jid),
            )
            cursor.use {
                if (it.moveToFirst()) it.toConversation() else null
            }
        }
    }

    fun getMessages(conversationJid: String, limit: Int = 50, beforeTimestamp: Long? = null): List<Message> {
        val db = open() ?: return emptyList()
        return db.use { d ->
            // Tiebreak on _rowid_ so messages sharing a wall-clock second
            // (storage is in seconds) still render in insertion order
            // instead of swapping unpredictably between polls.
            val query = if (beforeTimestamp != null) {
                "SELECT * FROM messages WHERE conversation_jid = ? AND timestamp < ? ORDER BY timestamp DESC, _rowid_ DESC LIMIT ?"
            } else {
                "SELECT * FROM messages WHERE conversation_jid = ? ORDER BY timestamp DESC, _rowid_ DESC LIMIT ?"
            }
            val args = if (beforeTimestamp != null) {
                arrayOf(conversationJid, beforeTimestamp.toString(), limit.toString())
            } else {
                arrayOf(conversationJid, limit.toString())
            }
            val cursor = d.rawQuery(query, args)
            cursor.use { it.toMessages() }
        }
    }

    fun getMessage(id: String): Message? {
        val db = open() ?: return null
        return db.use { d ->
            val cursor = d.rawQuery("SELECT * FROM messages WHERE id = ?", arrayOf(id))
            cursor.use {
                if (it.moveToFirst()) it.toMessage() else null
            }
        }
    }

    fun getReactions(messageIds: List<String>): Map<String, List<Reaction>> {
        if (messageIds.isEmpty()) return emptyMap()
        val db = open() ?: return emptyMap()
        return db.use { d ->
            val placeholders = messageIds.joinToString(",") { "?" }
            val cursor = d.rawQuery(
                "SELECT * FROM reactions WHERE message_id IN ($placeholders)",
                messageIds.toTypedArray(),
            )
            cursor.use { c ->
                val result = mutableMapOf<String, MutableList<Reaction>>()
                while (c.moveToNext()) {
                    val r = c.toReaction()
                    result.getOrPut(r.messageId) { mutableListOf() }.add(r)
                }
                result
            }
        }
    }

    fun getParticipants(conversationJid: String): List<Participant> {
        val db = open() ?: return emptyList()
        return db.use { d ->
            val cursor = d.rawQuery(
                "SELECT * FROM participants WHERE conversation_jid = ?",
                arrayOf(conversationJid),
            )
            cursor.use { it.toParticipants() }
        }
    }

    fun isPaired(): Boolean {
        val sessionDb = File(File(dbPath).parent, "whatsmeow.db")
        return sessionDb.exists() && sessionDb.length() > 0
    }

    // Cursor mappers

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

    private fun Cursor.toParticipants(): List<Participant> {
        val list = mutableListOf<Participant>()
        while (moveToNext()) {
            list.add(Participant(
                conversationJid = getString(getColumnIndexOrThrow("conversation_jid")),
                jid = getString(getColumnIndexOrThrow("jid")),
                displayName = getStringOrNull("display_name"),
                role = getString(getColumnIndexOrThrow("role")),
            ))
        }
        return list
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

private inline fun <T> SQLiteDatabase.use(block: (SQLiteDatabase) -> T): T {
    return try {
        block(this)
    } finally {
        close()
    }
}
