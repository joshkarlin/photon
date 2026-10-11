package app.photon.signal.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.photon.data.db.toMessage
import app.photon.data.db.toMessages
import app.photon.data.model.Message
import app.photon.signal.MessageKeys
import app.photon.signal.db.SignalMessageDatabase.Companion.STATUS_READ
import app.photon.signal.db.SignalMessageDatabase.Companion.STATUS_UNREAD

// Message rows: inserts (incoming deduped and counted unread), lookups, status and media.

fun SignalMessageDatabase.insertMessage(
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

/**
 * Store an incoming message and count it unread in one transaction.
 * Separate statements let a concurrent "chat opened" reset land between
 * the insert and the increment, leaving a stale unread badge on a
 * message the user was already looking at.
 *
 * Dedupes on [dedupePrefix] (the stable "{author}_{timestampMs}" key —
 * redeliveries mint a new random suffix) and on the exact id. Pass a
 * null prefix for ids that aren't message keys (call rows). [alreadyRead]
 * stores the row read without touching unread_count (a read sync for it
 * arrived first). Returns false, changing nothing, for a duplicate.
 */
fun SignalMessageDatabase.insertIncomingMessage(
    id: String,
    conversationJid: String,
    senderJid: String,
    timestamp: Long,
    contentType: String,
    textBody: String?,
    mediaMime: String? = null,
    mediaSize: Long? = null,
    replyToId: String? = null,
    rawProto: ByteArray? = null,
    dedupePrefix: String? = MessageKeys.prefixOf(id),
    alreadyRead: Boolean = false,
): Boolean {
    val db = writableDatabase
    db.beginTransaction()
    try {
        if (rowExists(db, id) || (dedupePrefix != null && prefixExists(db, dedupePrefix))) {
            return false
        }
        db.insertOrThrow(
            "messages", null,
            ContentValues().apply {
                put("id", id)
                put("conversation_jid", conversationJid)
                put("sender_jid", senderJid)
                put("timestamp", timestamp)
                put("content_type", contentType)
                put("text_body", textBody)
                put("media_mime", mediaMime)
                if (mediaSize != null) put("media_size", mediaSize)
                put("reply_to_id", replyToId)
                put("is_from_me", 0)
                put("status", if (alreadyRead) STATUS_READ else STATUS_UNREAD)
                if (rawProto != null) put("raw_proto", rawProto)
            },
        )
        setLastMessageIfNewer(db, conversationJid, id, timestamp)
        if (!alreadyRead) {
            db.execSQL(
                "UPDATE conversations SET unread_count = unread_count + 1 WHERE jid = ?",
                arrayOf(conversationJid),
            )
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    notifyChanged()
    return true
}

fun SignalMessageDatabase.getMessageRawProto(id: String): ByteArray? {
    readableDatabase.rawQuery(
        "SELECT raw_proto FROM messages WHERE id = ?", arrayOf(id),
    ).use { c ->
        if (!c.moveToFirst() || c.isNull(0)) return null
        return c.getBlob(0)
    }
}

fun SignalMessageDatabase.updateMessageStatus(id: String, status: String) {
    writableDatabase.execSQL(
        "UPDATE messages SET status = ? WHERE id = ?",
        arrayOf(status, id),
    )
    notifyChanged()
}

/** Remove a message and its reactions from the local DB. */
fun SignalMessageDatabase.deleteMessage(id: String) {
    writableDatabase.execSQL("DELETE FROM messages WHERE id = ?", arrayOf(id))
    // Reactions are keyed by the "{author}_{timestampMs}" prefix of the id.
    writableDatabase.execSQL(
        "DELETE FROM reactions WHERE message_id = ?",
        arrayOf(id.substringBeforeLast("_")),
    )
    notifyChanged()
}

fun SignalMessageDatabase.updateMediaUrl(id: String, url: String) {
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
fun SignalMessageDatabase.clearMediaUrlByPath(path: String) {
    writableDatabase.execSQL(
        "UPDATE messages SET media_url = NULL WHERE media_url = ?",
        arrayOf(path),
    )
    notifyChanged()
}

fun SignalMessageDatabase.getMessages(conversationJid: String, limit: Int = 50): List<Message> {
    return readableDatabase.rawQuery(
        // Tiebreak on _rowid_ so two messages sent in the same wall-clock
        // second still render in insertion order. Without this, sending
        // two messages back-to-back occasionally swaps them in the chat
        // (storage is in seconds; the wire timestamp is sub-second).
        "SELECT * FROM messages WHERE conversation_jid = ? ORDER BY timestamp DESC, _rowid_ DESC LIMIT ?",
        arrayOf(conversationJid, limit.toString()),
    ).use { it.toMessages(::signalStatus) }
}

fun SignalMessageDatabase.getMessage(id: String): Message? {
    return readableDatabase.rawQuery(
        "SELECT * FROM messages WHERE id = ?", arrayOf(id),
    ).use { if (it.moveToFirst()) it.toMessage(::signalStatus) else null }
}

/**
 * Find a message by its `"${authorAci}_${timestamp}"` stable prefix. Signal message
 * IDs get a random suffix when stored locally so reply quotes (which only know the
 * original sent timestamp + author) can't look up by exact ID.
 */
fun SignalMessageDatabase.findMessageByPrefix(prefix: String): Message? {
    // Range scan instead of LIKE — LIKE is case-insensitive in SQLite so
    // it can't use the PK index. IDs are ASCII "{aci}_{tsMs}_{rand}", so
    // ["prefix_", "prefix_\uFFFF") covers every suffix.
    return readableDatabase.rawQuery(
        "SELECT * FROM messages WHERE id >= ? AND id < ? LIMIT 1",
        arrayOf("${prefix}_", "${prefix}_\uFFFF"),
    ).use { if (it.moveToFirst()) it.toMessage(::signalStatus) else null }
}

/**
 * Status update for our own messages matched by the stable
 * "{aci}_{tsMs}" prefix — delivery/read receipts only carry the original
 * sent timestamp, not the random suffix we add for PK uniqueness. Same
 * index-friendly range form as findMessageByPrefix. Never regresses a
 * 'read' back to 'delivered'.
 */
fun SignalMessageDatabase.updateStatusByIdPrefix(prefix: String, status: String) {
    writableDatabase.execSQL(
        "UPDATE messages SET status = ? WHERE id >= ? AND id < ? AND is_from_me = 1 AND status != 'read'",
        arrayOf(status, "${prefix}_", "${prefix}_\uFFFF"),
    )
    notifyChanged()
}
