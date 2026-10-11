package app.photon.signal.db

import android.content.ContentValues
import app.photon.signal.db.SignalMessageDatabase.Companion.CONTENT_CALL
import app.photon.signal.db.SignalMessageDatabase.Companion.STATUS_DELETED
import app.photon.signal.db.SignalMessageDatabase.Companion.STATUS_READ
import app.photon.signal.db.SignalMessageDatabase.Companion.STATUS_SEEN
import app.photon.signal.db.SignalMessageDatabase.Companion.STATUS_UNREAD
import app.photon.signal.db.SignalMessageDatabase.ReadSyncResult

// Read state of incoming rows (received → seen → read, or deleted) and the unread counts it drives.

/**
 * The user opened the conversation in Photon: clear its unread count and
 * move unread incoming rows to 'seen' (viewed here, read sync pending).
 * Call rows never sync, so they go straight to 'read'. One transaction,
 * so a message inserted concurrently is either counted and marked seen,
 * or arrives afterwards and stays unread.
 */
fun SignalMessageDatabase.markConversationSeen(jid: String) {
    val db = writableDatabase
    var changed = 0
    db.beginTransaction()
    try {
        changed += db.update(
            "messages",
            ContentValues().apply { put("status", STATUS_READ) },
            "conversation_jid = ? AND is_from_me = 0 AND content_type = ? AND status IN (?, ?)",
            arrayOf(jid, CONTENT_CALL, STATUS_UNREAD, STATUS_SEEN),
        )
        changed += db.update(
            "messages",
            ContentValues().apply { put("status", STATUS_SEEN) },
            "conversation_jid = ? AND is_from_me = 0 AND status = ?",
            arrayOf(jid, STATUS_UNREAD),
        )
        changed += db.update(
            "conversations",
            ContentValues().apply { put("unread_count", 0) },
            "jid = ? AND unread_count != 0",
            arrayOf(jid),
        )
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    // Only emit when something changed: this runs on every chat-screen
    // load, and an unconditional emission just causes a requery.
    if (changed > 0) notifyChanged()
}

/**
 * Mark all incoming messages in a conversation read and clear its unread
 * count. A primary-device sent transcript is evidence that the user has
 * seen that conversation, even when the corresponding sync.read arrives
 * late or is not delivered to this linked device.
 *
 * Outgoing rows are intentionally untouched: their status is used for
 * delivery/read state and they are not part of the unread calculation.
 */
fun SignalMessageDatabase.markConversationRead(jid: String): Int {
    val db = writableDatabase
    var incomingChanged = 0
    var unreadCount = 0

    db.beginTransaction()
    try {
        db.rawQuery(
            "SELECT unread_count FROM conversations WHERE jid = ?",
            arrayOf(jid),
        ).use { cursor ->
            if (cursor.moveToFirst()) unreadCount = cursor.getInt(0)
        }
        db.rawQuery(
            """
            SELECT COUNT(*)
              FROM messages
             WHERE conversation_jid = ?
               AND is_from_me = 0
               AND status IN (?, ?)
            """.trimIndent(),
            arrayOf(jid, STATUS_UNREAD, STATUS_SEEN),
        ).use { cursor ->
            if (cursor.moveToFirst()) incomingChanged = cursor.getInt(0)
        }
        if (incomingChanged > 0) {
            db.execSQL(
                """
                UPDATE messages
                   SET status = ?
                 WHERE conversation_jid = ?
                   AND is_from_me = 0
                   AND status IN (?, ?)
                """.trimIndent(),
                arrayOf(STATUS_READ, jid, STATUS_UNREAD, STATUS_SEEN),
            )
        }
        db.execSQL(
            "UPDATE conversations SET unread_count = 0 WHERE jid = ?",
            arrayOf(jid),
        )
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }

    if (incomingChanged > 0 || unreadCount > 0) notifyChanged()
    return incomingChanged
}

/**
 * Incoming messages the user has seen in Photon whose read state hasn't
 * been synced to our other devices yet. The status column doubles as the
 * tracker: rows move "seen" → "read" once a SyncMessage.read covering
 * them has gone out, so each message is synced at most once. Call rows
 * are excluded: their ids aren't (author, timestamp) keys, so they could
 * never be synced and would be retried on every chat open.
 */
fun SignalMessageDatabase.getUnsyncedIncomingReads(jid: String): List<String> {
    readableDatabase.rawQuery(
        "SELECT id FROM messages WHERE conversation_jid = ? AND is_from_me = 0 " +
            "AND status = ? AND content_type != ?",
        arrayOf(jid, STATUS_SEEN, CONTENT_CALL),
    ).use { c ->
        val ids = mutableListOf<String>()
        while (c.moveToNext()) ids.add(c.getString(0))
        return ids
    }
}

/** Mark seen rows read-synced. Rows deleted meanwhile keep their 'deleted' status. */
fun SignalMessageDatabase.markMessagesRead(ids: List<String>) {
    if (ids.isEmpty()) return
    val placeholders = ids.joinToString(",") { "?" }
    writableDatabase.execSQL(
        "UPDATE messages SET status = ? WHERE status = ? AND id IN ($placeholders)",
        arrayOf<Any?>(STATUS_READ, STATUS_SEEN, *ids.toTypedArray()),
    )
    // No notifyChanged(): incoming status isn't rendered anywhere, and
    // this runs from the chat-open markRead path — a change emission
    // here would just cause a redundant requery.
}

/**
 * Mark a message deleted-for-everyone by its "{author}_{timestampMs}"
 * prefix. Incoming remote-deletes only know author + timestamp, not the
 * "_{rand}" suffix on the stored id, so an exact-id match never hit. Blanks
 * the body/media so the shared UI renders it as "[ deleted ]", matching the
 * WhatsApp revoke behaviour.
 */
fun SignalMessageDatabase.markDeletedByPrefix(prefix: String): List<String> {
    val lowerBound = "${prefix}_"
    val upperBound = "${prefix}_\uFFFF"
    val unreadRemoved = linkedMapOf<String, Int>()
    val db = writableDatabase
    val cleared: List<String>
    db.beginTransaction()
    try {
        // A deleted message that was still unread must stop counting,
        // or the badge (and its notification) outlive the message.
        db.rawQuery(
            "SELECT conversation_jid, COUNT(*) FROM messages " +
                "WHERE id >= ? AND id < ? AND is_from_me = 0 AND status = ? GROUP BY conversation_jid",
            arrayOf(lowerBound, upperBound, STATUS_UNREAD),
        ).use { c -> while (c.moveToNext()) unreadRemoved[c.getString(0)] = c.getInt(1) }
        db.execSQL(
            "UPDATE messages SET text_body = '', content_type = 'text', media_url = NULL, status = ? " +
                "WHERE id >= ? AND id < ?",
            arrayOf(STATUS_DELETED, lowerBound, upperBound),
        )
        db.execSQL("DELETE FROM reactions WHERE message_id = ?", arrayOf(prefix))
        cleared = decrementUnread(db, unreadRemoved)
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    notifyChanged()
    return cleared
}

/**
 * Apply a read sync from another linked device to the matching incoming
 * message. SyncMessage.read carries the author + timestamp, while local
 * message ids add a random suffix.
 *
 * Only rows that were actually unread decrement unread_count. Rows the
 * user already saw in Photon ('seen') just become 'read': their unread
 * was cleared when the chat was opened, so subtracting them again would
 * eat the count (and cancel the notification) of newer messages.
 */
fun SignalMessageDatabase.markIncomingReadByPrefix(prefix: String): ReadSyncResult {
    val lowerBound = "${prefix}_"
    val upperBound = "${prefix}_\uFFFF"
    val unreadByConversation = linkedMapOf<String, Int>()
    var matched = false
    var changed = 0
    val cleared: List<String>
    val db = writableDatabase

    db.beginTransaction()
    try {
        db.rawQuery(
            """
            SELECT conversation_jid, status
              FROM messages
             WHERE id >= ? AND id < ?
               AND is_from_me = 0
            """,
            arrayOf(lowerBound, upperBound),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                matched = true
                if (cursor.getString(1) == STATUS_UNREAD) {
                    val jid = cursor.getString(0)
                    unreadByConversation[jid] = (unreadByConversation[jid] ?: 0) + 1
                }
            }
        }

        if (matched) {
            changed = db.update(
                "messages",
                ContentValues().apply { put("status", STATUS_READ) },
                "id >= ? AND id < ? AND is_from_me = 0 AND status IN (?, ?)",
                arrayOf(lowerBound, upperBound, STATUS_UNREAD, STATUS_SEEN),
            )
        }
        cleared = decrementUnread(db, unreadByConversation)
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }

    if (changed > 0) notifyChanged()
    return ReadSyncResult(matched, cleared)
}
