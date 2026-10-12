package app.photon.signal.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.photon.data.db.toMessage
import app.photon.data.db.toMessages
import app.photon.data.model.Message
import app.photon.signal.MessageKeys
import app.photon.signal.PlannedRow
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
    expiresIn: Int = 0,
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
            put("expires_in", expiresIn)
        },
        SQLiteDatabase.CONFLICT_IGNORE,
    )
    notifyChanged()
}

/** One planned row as a messages row; see [insertIncomingRows] and [insertOwnRows]. */
private fun PlannedRow.values(
    id: String,
    conversationJid: String,
    senderJid: String,
    timestamp: Long,
    replyToId: String?,
    isFromMe: Boolean,
    status: String,
    expiresIn: Int,
    expiresAt: Long,
) = ContentValues().apply {
    put("id", id)
    put("conversation_jid", conversationJid)
    put("sender_jid", senderJid)
    put("timestamp", timestamp)
    put("content_type", contentType)
    put("text_body", text)
    attachment?.let { pointer ->
        put("media_mime", pointer.contentType)
        pointer.size?.let { put("media_size", it.toLong()) }
        // The serialized pointer, so the media can be downloaded when tapped.
        put("raw_proto", pointer.encode())
    }
    put("reply_to_id", replyToId)
    put("is_from_me", if (isFromMe) 1 else 0)
    put("status", status)
    put("expires_in", expiresIn)
    put("expires_at", expiresAt)
}

/**
 * Store an incoming message as its [rows] (plannedRows: one per attachment,
 * contact, poll or body), each counted unread, in one transaction. The
 * first row is [messageId], the rest [MessageKeys.partId]s of it; only the
 * first quotes [replyToId]. Each row disappears [expiresIn] seconds after
 * it's read (0: never).
 *
 * Dedupes like [insertIncomingMessage], on the message's
 * "{author}_{timestampMs}" prefix: returns false, changing nothing, for a
 * redelivery. [alreadyRead] stores it read (a read sync came first), with
 * its clock started. A new message unarchives its chat
 * ([unarchiveForNewMessage]).
 */
internal fun SignalMessageDatabase.insertIncomingRows(
    messageId: String,
    rows: List<PlannedRow>,
    conversationJid: String,
    senderJid: String,
    timestamp: Long,
    replyToId: String? = null,
    expiresIn: Int = 0,
    alreadyRead: Boolean = false,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    if (rows.isEmpty()) return false
    val db = writableDatabase
    val expiresAt = if (alreadyRead && expiresIn > 0) nowMs + expiresIn * 1000L else 0L
    db.beginTransaction()
    try {
        if (rowExists(db, messageId) || prefixExists(db, MessageKeys.prefixOf(messageId))) return false
        var lastId = messageId
        rows.forEachIndexed { index, row ->
            lastId = MessageKeys.partId(messageId, index)
            db.insertOrThrow(
                "messages", null,
                row.values(
                    lastId, conversationJid, senderJid, timestamp,
                    replyToId.takeIf { index == 0 }, isFromMe = false,
                    status = if (alreadyRead) STATUS_READ else STATUS_UNREAD,
                    expiresIn = expiresIn, expiresAt = expiresAt,
                ),
            )
        }
        setLastMessageIfNewer(db, conversationJid, lastId, timestamp)
        if (!alreadyRead) adjustUnread(db, conversationJid, rows.size)
        unarchiveForNewMessage(db, conversationJid, nowMs / 1000)
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    notifyChanged()
    if (expiresAt > 0) notifyExpiryScheduled()
    return true
}

/**
 * Store the user's own message from another device (a sent transcript) as
 * its [rows], "sent", unless its prefix is already stored. Its clock
 * started at [expireStartMs] when it has a timer.
 */
internal fun SignalMessageDatabase.insertOwnRows(
    messageId: String,
    rows: List<PlannedRow>,
    conversationJid: String,
    senderJid: String,
    timestamp: Long,
    replyToId: String? = null,
    expiresIn: Int = 0,
    expireStartMs: Long = 0,
): Boolean {
    if (rows.isEmpty()) return false
    val db = writableDatabase
    val expiresAt = if (expiresIn > 0) expireStartMs + expiresIn * 1000L else 0L
    db.beginTransaction()
    try {
        if (rowExists(db, messageId) || prefixExists(db, MessageKeys.prefixOf(messageId))) return false
        var lastId = messageId
        rows.forEachIndexed { index, row ->
            lastId = MessageKeys.partId(messageId, index)
            db.insertOrThrow(
                "messages", null,
                row.values(
                    lastId, conversationJid, senderJid, timestamp,
                    replyToId.takeIf { index == 0 }, isFromMe = true, status = "sent",
                    expiresIn = expiresIn, expiresAt = expiresAt,
                ),
            )
        }
        setLastMessageIfNewer(db, conversationJid, lastId, timestamp)
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    notifyChanged()
    if (expiresAt > 0) notifyExpiryScheduled()
    return true
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

/**
 * Remove a row and, once none of its message's rows is left, the message's
 * reactions from the local DB.
 */
fun SignalMessageDatabase.deleteMessage(id: String) {
    val db = writableDatabase
    db.execSQL("DELETE FROM messages WHERE id = ?", arrayOf(id))
    // Reactions are keyed by the "{author}_{timestampMs}" prefix of the id.
    val prefix = MessageKeys.prefixOf(id)
    if (!prefixExists(db, prefix)) db.execSQL("DELETE FROM reactions WHERE message_id = ?", arrayOf(prefix))
    notifyChanged()
}

/**
 * Remove every row of the message keyed [prefix] (all its attachments) and
 * its reactions: our own delete-for-everyone, which the other devices apply
 * to the whole message.
 */
fun SignalMessageDatabase.deleteMessagesByPrefix(prefix: String) {
    val db = writableDatabase
    db.beginTransaction()
    try {
        val jids = db.rawQuery(
            "SELECT DISTINCT conversation_jid FROM messages WHERE id >= ? AND id < ?",
            arrayOf("${prefix}_", "${prefix}_\uFFFF"),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        db.execSQL("DELETE FROM messages WHERE id >= ? AND id < ?", arrayOf("${prefix}_", "${prefix}_\uFFFF"))
        db.execSQL("DELETE FROM reactions WHERE message_id = ?", arrayOf(prefix))
        db.execSQL("DELETE FROM message_aliases WHERE target_id NOT IN (SELECT id FROM messages)")
        jids.forEach { repairLastMessageId(db, it) }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
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

/**
 * SQL for a row whose disappearing clock hasn't run out at [nowMs], for the
 * queries the UI reads: the expiry sweep deletes it soon, but its timer
 * can't wake it from deep sleep, so a row can outlive its time until then.
 */
internal fun notExpired(column: String = "expires_at", nowMs: Long = System.currentTimeMillis()): String =
    "($column = 0 OR $column > $nowMs)"

/** A conversation's messages, newest first, leaving out any whose time is up. */
fun SignalMessageDatabase.getMessages(
    conversationJid: String,
    limit: Int = 50,
    nowMs: Long = System.currentTimeMillis(),
): List<Message> {
    return readableDatabase.rawQuery(
        // Tiebreak on _rowid_ so two messages sent in the same wall-clock
        // second still render in insertion order. Without this, sending
        // two messages back-to-back occasionally swaps them in the chat
        // (storage is in seconds; the wire timestamp is sub-second).
        "SELECT * FROM messages WHERE conversation_jid = ? AND ${notExpired(nowMs = nowMs)} " +
            "ORDER BY timestamp DESC, _rowid_ DESC LIMIT ?",
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
fun SignalMessageDatabase.findMessageByPrefix(prefix: String): Message? = findMessageByPrefix(prefix, liveOnly = false)

/**
 * [findMessageByPrefix] for the UI (a reply's quote): with [liveOnly], a
 * message whose disappearing time is up isn't found.
 */
fun SignalMessageDatabase.findMessageByPrefix(prefix: String, liveOnly: Boolean): Message? {
    // Range scan instead of LIKE — LIKE is case-insensitive in SQLite so
    // it can't use the PK index. IDs are ASCII "{aci}_{tsMs}_{rand}", so
    // ["prefix_", "prefix_\uFFFF") covers every suffix. The first row of a
    // message stored as several sorts first. A quote of an edited message
    // may name the edit (see resolveMessagePrefix).
    val resolved = resolveMessagePrefix(prefix)
    val live = if (liveOnly) " AND ${notExpired()}" else ""
    return readableDatabase.rawQuery(
        "SELECT * FROM messages WHERE id >= ? AND id < ?$live ORDER BY id LIMIT 1",
        arrayOf("${resolved}_", "${resolved}_\uFFFF"),
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

/** Whether a message keyed "{author}_{timestampMs}" [prefix] is stored (a redelivery when it is). */
fun SignalMessageDatabase.isMessageStored(prefix: String): Boolean = prefixExists(readableDatabase, prefix)
