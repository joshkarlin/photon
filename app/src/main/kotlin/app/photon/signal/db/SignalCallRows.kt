package app.photon.signal.db

import android.content.ContentValues
import app.photon.data.db.toMessages
import app.photon.data.model.Message
import app.photon.signal.db.SignalMessageDatabase.CallRowChange
import app.photon.signal.db.SignalMessageDatabase.Companion.CONTENT_CALL
import app.photon.signal.db.SignalMessageDatabase.Companion.STATUS_READ
import app.photon.signal.db.SignalMessageDatabase.Companion.STATUS_UNREAD

// Call log rows (see SignalCalls): outcome in text_body, read state in status.

/**
 * Insert or update a call row. The outcome ("Missed voice call", …) lives
 * in text_body; status stays read state, so [unread] decides whether the
 * row counts towards unread_count. Insert/update and the count change
 * are one transaction.
 */
fun SignalMessageDatabase.upsertCallRow(
    id: String,
    conversationJid: String,
    senderJid: String,
    timestamp: Long,
    text: String,
    unread: Boolean,
): CallRowChange {
    val db = writableDatabase
    var existed = false
    var wasUnread = false
    var unreadCount = 0
    db.beginTransaction()
    try {
        val previousStatus = db.rawQuery("SELECT status FROM messages WHERE id = ?", arrayOf(id))
            .use { if (it.moveToFirst()) it.getString(0) else null }
        if (previousStatus == null) {
            db.insertOrThrow(
                "messages", null,
                ContentValues().apply {
                    put("id", id)
                    put("conversation_jid", conversationJid)
                    put("sender_jid", senderJid)
                    put("timestamp", timestamp)
                    put("content_type", CONTENT_CALL)
                    put("text_body", text)
                    put("is_from_me", 0)
                    put("status", if (unread) STATUS_UNREAD else STATUS_READ)
                },
            )
            setLastMessageIfNewer(db, conversationJid, id, timestamp)
            if (unread) adjustUnread(db, conversationJid, +1)
        } else {
            existed = true
            wasUnread = previousStatus == STATUS_UNREAD
            val status = when {
                unread -> STATUS_UNREAD
                wasUnread -> STATUS_READ
                else -> previousStatus
            }
            db.update(
                "messages",
                ContentValues().apply {
                    put("text_body", text)
                    put("status", status)
                },
                "id = ?", arrayOf(id),
            )
            if (unread && !wasUnread) adjustUnread(db, conversationJid, +1)
            if (!unread && wasUnread) adjustUnread(db, conversationJid, -1)
        }
        unreadCount = unreadCountOf(db, conversationJid)
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    notifyChanged()
    return CallRowChange(conversationJid, existed, wasUnread, unread, unreadCount)
}

/** Remove a call row (deleted from the call log on another device). */
fun SignalMessageDatabase.deleteCallRow(id: String): CallRowChange? {
    val db = writableDatabase
    val change: CallRowChange
    db.beginTransaction()
    try {
        val row = db.rawQuery(
            "SELECT conversation_jid, status FROM messages WHERE id = ? AND content_type = ?",
            arrayOf(id, CONTENT_CALL),
        ).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null } ?: return null
        val (jid, status) = row
        db.delete("messages", "id = ?", arrayOf(id))
        val wasUnread = status == STATUS_UNREAD
        if (wasUnread) adjustUnread(db, jid, -1)
        change = CallRowChange(jid, true, wasUnread, false, unreadCountOf(db, jid))
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    notifyChanged()
    return change
}

/**
 * Call row id for a Signal callId, whatever conversation it's in. Used
 * when a call-event sync's conversation id can't be mapped to a jid.
 */
fun SignalMessageDatabase.findCallRowId(callId: Long): String? =
    readableDatabase.rawQuery(
        "SELECT id FROM messages WHERE content_type = ? AND id LIKE ? ESCAPE '\\' LIMIT 1",
        arrayOf(CONTENT_CALL, "%\\_$callId"),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

/** Call rows whose text is one of [texts], such as every row still ringing. */
fun SignalMessageDatabase.getCallRowsWithText(texts: Collection<String>): List<Message> {
    if (texts.isEmpty()) return emptyList()
    return readableDatabase.rawQuery(
        "SELECT * FROM messages WHERE content_type = ? AND text_body IN (${texts.joinToString(",") { "?" }})",
        arrayOf(CONTENT_CALL, *texts.toTypedArray()),
    ).use { it.toMessages() }
}

/**
 * Mark unread call rows up to [upToTimestamp] (seconds) read — a call-log
 * "mark as read" from another device, for one conversation or (null) all.
 * Returns conversations whose unread count reached zero.
 */
fun SignalMessageDatabase.markCallRowsRead(conversationJid: String?, upToTimestamp: Long): List<String> {
    val where = buildString {
        append("content_type = ? AND is_from_me = 0 AND status = ? AND timestamp <= ?")
        if (conversationJid != null) append(" AND conversation_jid = ?")
    }
    val args = listOfNotNull(CONTENT_CALL, STATUS_UNREAD, upToTimestamp.toString(), conversationJid)
        .toTypedArray()
    val counts = linkedMapOf<String, Int>()
    val db = writableDatabase
    val cleared: List<String>
    db.beginTransaction()
    try {
        db.rawQuery("SELECT conversation_jid, COUNT(*) FROM messages WHERE $where GROUP BY conversation_jid", args)
            .use { c -> while (c.moveToNext()) counts[c.getString(0)] = c.getInt(1) }
        if (counts.isNotEmpty()) {
            db.update("messages", ContentValues().apply { put("status", STATUS_READ) }, where, args)
        }
        cleared = decrementUnread(db, counts)
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    if (counts.isNotEmpty()) notifyChanged()
    return cleared
}
