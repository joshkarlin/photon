package app.photon.signal.db

import android.database.sqlite.SQLiteDatabase
import app.photon.data.model.ContentTypes
import app.photon.signal.ChatTimer
import app.photon.signal.MessageKeys
import app.photon.signal.db.SignalMessageDatabase.Companion.CONTENT_CALL
import app.photon.signal.db.SignalMessageDatabase.Companion.STATUS_READ
import app.photon.signal.db.SignalMessageDatabase.Companion.STATUS_UNREAD
import app.photon.signal.db.SignalMessageDatabase.RemoteDeleteChange
import app.photon.signal.disappearingMessagesLine
import app.photon.signal.syncedTimerUpdate
import java.util.UUID

// Disappearing messages: each chat's timer (conversations.expire_timer and
// expire_timer_version; a group's timer_revision), the line a change adds to
// the thread, and each message's clock (messages.expires_in, the timer it
// was sent with in seconds, and expires_at, the epoch ms it goes; 0 until
// its clock starts).
//
// Clocks start as Signal's do: our own messages when sent (or, from another
// device, when that device says it started them), other people's when the
// user has read them, here or on another device. An unread message never
// disappears.

/** [jid]'s timer, or [ChatTimer.DEFAULT] for a chat with no row. */
fun SignalMessageDatabase.chatTimer(jid: String): ChatTimer = chatTimer(readableDatabase, jid)

private fun chatTimer(db: SQLiteDatabase, jid: String): ChatTimer = storedTimer(db, jid) ?: ChatTimer.DEFAULT

private fun storedTimer(db: SQLiteDatabase, jid: String): ChatTimer? =
    db.rawQuery("SELECT expire_timer, expire_timer_version FROM conversations WHERE jid = ?", arrayOf(jid))
        .use { if (it.moveToFirst()) ChatTimer(it.getInt(0), it.getInt(1)) else null }

/**
 * Apply the timer a message in 1:1 chat [jid] carried: [update] says what
 * the stored timer becomes (incomingTimerUpdate or syncedTimerUpdate), or
 * null to leave it. A new timer value also adds its line to the thread, at
 * [lineTimestamp] (seconds), before the message that carried it. A chat
 * with no row is left alone. Returns the timer now stored when it changed.
 */
fun SignalMessageDatabase.updateDmTimer(
    jid: String,
    lineTimestamp: Long,
    update: (ChatTimer) -> ChatTimer?,
): ChatTimer? {
    val db = writableDatabase
    val updated: ChatTimer
    db.beginTransaction()
    try {
        val stored = storedTimer(db, jid) ?: return null
        updated = update(stored) ?: return null
        db.execSQL(
            "UPDATE conversations SET expire_timer = ?, expire_timer_version = ? WHERE jid = ?",
            arrayOf<Any?>(updated.seconds, updated.version, jid),
        )
        if (updated.seconds != stored.seconds) {
            insertSystemLine(db, jid, lineTimestamp, disappearingMessagesLine(updated.seconds))
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    notifyChanged()
    return updated
}

/** Whether group [jid]'s timer has been read from its state (always true for a chat that isn't a group). */
fun SignalMessageDatabase.groupTimerKnown(jid: String): Boolean =
    readableDatabase.rawQuery("SELECT is_group, timer_revision FROM conversations WHERE jid = ?", arrayOf(jid))
        .use { !it.moveToFirst() || it.getInt(0) == 0 || it.getInt(1) >= 0 }

/**
 * A group's timer, read from its state at group [revision]. Groups ignore
 * the timer on each message, as Signal does: the group's state is the only
 * source. A change since the last read adds its line to the thread; the
 * first read (a new group, or every group after the v7 upgrade) is silent.
 * A read older than the one stored is ignored. Returns whether the timer
 * changed.
 */
fun SignalMessageDatabase.updateGroupTimer(
    jid: String,
    seconds: Int,
    revision: Int,
    nowSeconds: Long = System.currentTimeMillis() / 1000,
): Boolean {
    val db = writableDatabase
    var changed = false
    db.beginTransaction()
    try {
        val (stored, readAt) = db.rawQuery(
            "SELECT expire_timer, timer_revision FROM conversations WHERE jid = ?", arrayOf(jid),
        ).use { if (it.moveToFirst()) it.getInt(0) to it.getInt(1) else return false }
        if (revision < readAt) return false
        db.execSQL(
            "UPDATE conversations SET expire_timer = ?, expire_timer_version = 1, timer_revision = ? WHERE jid = ?",
            arrayOf<Any?>(seconds, revision, jid),
        )
        if (readAt >= 0 && seconds != stored) {
            insertSystemLine(db, jid, nowSeconds, disappearingMessagesLine(seconds))
            changed = true
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    notifyChanged()
    return changed
}

/**
 * The timer the primary's contact sync gives [aci] (with its version; a
 * timer without one is ignored, as it can't be ordered against what
 * messages said). It follows the sent-transcript rule and adds no line.
 * Kept on the contact too, so a chat Photon starts with them later (New
 * message) sends with it from the first message.
 */
fun SignalMessageDatabase.updateTimerFromContactSync(aci: String, seconds: Int, version: Int) {
    val db = writableDatabase
    db.beginTransaction()
    try {
        db.execSQL(
            "UPDATE contacts SET expire_timer = ?, expire_timer_version = ? " +
                "WHERE jid = ? AND (expire_timer_version IS NULL OR expire_timer_version <= ?)",
            arrayOf<Any?>(seconds, version, aci, version),
        )
        val exists = db.rawQuery("SELECT 1 FROM conversations WHERE jid = ? AND is_group = 0", arrayOf(aci))
            .use { it.moveToFirst() }
        if (exists) {
            syncedTimerUpdate(chatTimer(db, aci), seconds, version)?.let { updated ->
                db.execSQL(
                    "UPDATE conversations SET expire_timer = ?, expire_timer_version = ? WHERE jid = ?",
                    arrayOf<Any?>(updated.seconds, updated.version, aci),
                )
            }
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    notifyChanged()
}

/**
 * A [ContentTypes.SYSTEM] row: read, never counted unread, and keyed apart
 * from messages (its id isn't an "{author}_{ts}" key, so no read sync,
 * reaction or delete can match it).
 */
private fun SignalMessageDatabase.insertSystemLine(db: SQLiteDatabase, jid: String, timestamp: Long, text: String) {
    val id = "system_${timestamp}_${UUID.randomUUID().toString().take(8)}"
    db.execSQL(
        "INSERT INTO messages (id, conversation_jid, sender_jid, timestamp, content_type, text_body, is_from_me, status) " +
            "VALUES (?, ?, '', ?, ?, ?, 0, ?)",
        arrayOf<Any?>(id, jid, timestamp, ContentTypes.SYSTEM, text, STATUS_READ),
    )
    setLastMessageIfNewer(db, jid, id, timestamp)
}

/**
 * Start the clock of incoming rows matching [where] that have been read
 * (anything but unread) and have a timer but no clock yet. Runs inside the
 * caller's transaction; returns how many started, and the caller then
 * calls [SignalMessageDatabase.notifyExpiryScheduled].
 */
internal fun startIncomingClocks(db: SQLiteDatabase, nowMs: Long, where: String, vararg args: String): Int =
    db.compileStatement(
        "UPDATE messages SET expires_at = ? + expires_in * 1000 " +
            "WHERE expires_in > 0 AND expires_at = 0 AND is_from_me = 0 AND status != ? AND ($where)",
    ).use { statement ->
        statement.bindLong(1, nowMs)
        statement.bindString(2, STATUS_UNREAD)
        args.forEachIndexed { i, arg -> statement.bindString(i + 3, arg) }
        statement.executeUpdateDelete()
    }

/** Our own message went out: it's "sent", and its clock starts now. */
fun SignalMessageDatabase.markOutgoingSent(id: String, nowMs: Long = System.currentTimeMillis()) {
    val started = writableDatabase.compileStatement(
        "UPDATE messages SET status = 'sent', expires_at = CASE WHEN expires_in > 0 AND expires_at = 0 " +
            "THEN ? + expires_in * 1000 ELSE expires_at END WHERE id = ?",
    ).use { statement ->
        statement.bindLong(1, nowMs)
        statement.bindString(2, id)
        statement.executeUpdateDelete()
    }
    notifyChanged()
    if (started > 0) notifyExpiryScheduled()
}

/** The timer an outgoing row is sent with (a retry sends with the chat's timer now). */
fun SignalMessageDatabase.setExpiresIn(id: String, seconds: Int) {
    writableDatabase.execSQL("UPDATE messages SET expires_in = ? WHERE id = ? AND expires_at = 0", arrayOf<Any?>(seconds, id))
}

/** When the next message disappears (epoch ms), or null when no clock is running. */
fun SignalMessageDatabase.nextExpiry(): Long? =
    readableDatabase.rawQuery("SELECT MIN(expires_at) FROM messages WHERE expires_at > 0", null)
        .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

/**
 * What an expiry sweep removed: [count] rows, the files they had
 * downloaded or sent ([mediaPaths]), and for each conversation that lost
 * one, what its message notification must do ([changes], as after a
 * delete-for-everyone).
 */
data class ExpiredMessages(val count: Int, val mediaPaths: List<String>, val changes: List<RemoteDeleteChange>) {
    companion object {
        val NONE = ExpiredMessages(0, emptyList(), emptyList())
    }
}

/**
 * Delete every message whose clock ran out by [nowMs], with its reactions
 * and edit aliases, in one transaction. An unread row stops counting (a
 * clock only starts once read, but the count must never outlive a row),
 * and each conversation keeps pointing at a row that still exists.
 */
fun SignalMessageDatabase.deleteExpiredMessages(nowMs: Long = System.currentTimeMillis()): ExpiredMessages {
    data class Row(val id: String, val jid: String, val mediaUrl: String?, val unread: Boolean, val rowid: Long)
    val db = writableDatabase
    val result: ExpiredMessages
    db.beginTransaction()
    try {
        val rows = db.rawQuery(
            "SELECT id, conversation_jid, media_url, is_from_me, status, rowid FROM messages " +
                "WHERE expires_at > 0 AND expires_at <= ?",
            arrayOf(nowMs.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Row(
                            id = c.getString(0), jid = c.getString(1),
                            mediaUrl = if (c.isNull(2)) null else c.getString(2),
                            unread = c.getInt(3) == 0 && c.getString(4) == STATUS_UNREAD,
                            rowid = c.getLong(5),
                        ),
                    )
                }
            }
        }
        if (rows.isEmpty()) return ExpiredMessages.NONE

        for (row in rows) db.execSQL("DELETE FROM messages WHERE id = ?", arrayOf(row.id))
        for (prefix in rows.map { MessageKeys.prefixOf(it.id) }.distinct()) {
            if (!prefixExists(db, prefix)) db.execSQL("DELETE FROM reactions WHERE message_id = ?", arrayOf(prefix))
        }
        db.execSQL("DELETE FROM message_aliases WHERE target_id NOT IN (SELECT id FROM messages)")
        decrementUnread(db, rows.filter { it.unread }.groupingBy { it.jid }.eachCount())

        val newestGone = rows.groupBy { it.jid }.mapValues { (_, gone) -> gone.maxOf { it.rowid } }
        val changes = newestGone.map { (jid, rowid) ->
            repairLastMessageId(db, jid)
            val newer = db.rawQuery(
                "SELECT 1 FROM messages WHERE conversation_jid = ? AND is_from_me = 0 AND status = ? " +
                    "AND content_type != ? AND rowid > ? LIMIT 1",
                arrayOf(jid, STATUS_UNREAD, CONTENT_CALL, rowid.toString()),
            ).use { it.moveToFirst() }
            RemoteDeleteChange(jid, unreadCountOf(db, jid), newer)
        }
        result = ExpiredMessages(rows.size, rows.mapNotNull { it.mediaUrl }, changes)
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    notifyChanged()
    return result
}

/** Point [jid]'s last_message_id at its newest row if the one it names is gone. */
internal fun repairLastMessageId(db: SQLiteDatabase, jid: String) {
    db.execSQL(
        """
        UPDATE conversations
           SET last_message_id = (
               SELECT id FROM messages WHERE conversation_jid = ?
                ORDER BY timestamp DESC, rowid DESC LIMIT 1
           )
         WHERE jid = ?
           AND NOT EXISTS (SELECT 1 FROM messages WHERE id = conversations.last_message_id)
        """.trimIndent(),
        arrayOf(jid, jid),
    )
}
