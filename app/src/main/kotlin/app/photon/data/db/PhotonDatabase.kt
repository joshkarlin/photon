package app.photon.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.Participant
import app.photon.data.model.Reaction
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

    // One long-lived read-only handle. WAL mode lets this reader coexist with
    // the Go bridge's writer; opening per-query would re-parse the header and
    // start with a cold page cache 2-6 times per second under the polling UI.
    @Volatile private var handle: SQLiteDatabase? = null

    @Synchronized
    private fun open(): SQLiteDatabase? {
        handle?.takeIf { it.isOpen }?.let { return it }
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
        ).also {
            handle = it
            hasOpenedOnce = true
        }
    }

    /**
     * Drop the cached handle. Must be called after the underlying file is
     * replaced (WhatsApp Reset deletes messages.db) — a handle to the deleted
     * inode would otherwise keep serving the old data forever.
     */
    @Synchronized
    fun invalidate() {
        try { handle?.close() } catch (_: Exception) {}
        handle = null
    }

    private fun <T> query(block: (SQLiteDatabase) -> T): T? {
        val db = open() ?: return null
        return try {
            block(db)
        } catch (e: SQLiteException) {
            // Stale handle (file replaced or FBE-locked) — drop it so the
            // next poll tick reopens, and let the caller's resilience layer
            // absorb this attempt.
            invalidate()
            throw e
        }
    }

    // Explicit column list for the chat hot path: messages.raw_proto holds the
    // full serialized protobuf (inline thumbnails included) and is never read
    // by the UI — SELECT * would drag those blobs through the CursorWindow on
    // every poll tick.
    private val messageColumns = "id, conversation_jid, sender_jid, timestamp, content_type, " +
        "text_body, media_url, media_mime, media_size, thumbnail_path, sticker_pack_id, " +
        "reply_to_id, edit_version, is_from_me, status"

    fun getConversations(): List<Conversation> = query { d ->
        d.rawQuery("SELECT * FROM conversations ORDER BY last_timestamp DESC", null)
            .use { it.toConversations() }
    } ?: emptyList()

    fun getConversation(jid: String): Conversation? = query { d ->
        d.rawQuery("SELECT * FROM conversations WHERE jid = ?", arrayOf(jid)).use {
            if (it.moveToFirst()) it.toConversation() else null
        }
    }

    fun getMessages(conversationJid: String, limit: Int = 50): List<Message> = query { d ->
        // Tiebreak on _rowid_ so messages sharing a wall-clock second
        // (storage is in seconds) still render in insertion order
        // instead of swapping unpredictably between polls.
        d.rawQuery(
            "SELECT $messageColumns FROM messages WHERE conversation_jid = ? " +
                "ORDER BY timestamp DESC, _rowid_ DESC LIMIT ?",
            arrayOf(conversationJid, limit.toString()),
        ).use { it.toMessages() }
    } ?: emptyList()

    fun getMessage(id: String): Message? = query { d ->
        d.rawQuery("SELECT $messageColumns FROM messages WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) it.toMessage() else null
        }
    }

    fun getReactions(messageIds: List<String>): Map<String, List<Reaction>> {
        if (messageIds.isEmpty()) return emptyMap()
        return query { d ->
            val placeholders = messageIds.joinToString(",") { "?" }
            d.rawQuery(
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
        } ?: emptyMap()
    }

    fun getParticipants(conversationJid: String): List<Participant> = query { d ->
        d.rawQuery(
            "SELECT * FROM participants WHERE conversation_jid = ?",
            arrayOf(conversationJid),
        ).use { it.toParticipants() }
    } ?: emptyList()
}
