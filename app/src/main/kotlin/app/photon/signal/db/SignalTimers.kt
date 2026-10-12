package app.photon.signal.db

import android.database.sqlite.SQLiteDatabase
import app.photon.data.model.ContentTypes
import app.photon.signal.ChatTimer
import app.photon.signal.MessageKeys
import app.photon.signal.db.SignalMessageDatabase.Companion.CONTENT_CALL
import app.photon.signal.db.SignalMessageDatabase.Companion.STATUS_READ
import app.photon.signal.db.SignalMessageDatabase.Companion.STATUS_UNREAD
import app.photon.signal.db.SignalMessageDatabase.RemoteDeleteChange
import app.photon.signal.contactSyncTimerUpdate
import app.photon.signal.disappearingMessagesLine
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

/**
 * [jid]'s timer, or null when Photon doesn't know it: a chat with no row, a
 * DM whose timer hasn't been learned, or a group whose state has never been
 * read. A group read at an older revision gives the timer it had then
 * ([groupTimerCurrent] says whether it's worth reading again first): groups
 * ignore the timer on messages, so a stale one can't change the group's
 * setting, and the message still disappears. A message to a chat with no
 * known timer goes at version 0 (ChatTimer.UNKNOWN).
 */
fun SignalMessageDatabase.chatTimer(jid: String): ChatTimer? = chatTimer(readableDatabase, jid)

private fun chatTimer(db: SQLiteDatabase, jid: String): ChatTimer? =
    db.rawQuery(
        "SELECT is_group, expire_timer, expire_timer_version, timer_revision, group_revision FROM conversations WHERE jid = ?",
        arrayOf(jid),
    ).use { c ->
        if (!c.moveToFirst() || c.isNull(1)) return null
        if (c.getInt(0) == 1) {
            if (c.getInt(3) < 0) null else ChatTimer(c.getInt(1), 1)
        } else {
            ChatTimer(c.getInt(1), if (c.isNull(2)) 0 else c.getInt(2))
        }
    }

/** A DM row's stored timer: null for no row, else the timer or null when not learned. */
private fun storedDmTimer(db: SQLiteDatabase, jid: String): Pair<Boolean, ChatTimer?> =
    db.rawQuery("SELECT expire_timer, expire_timer_version FROM conversations WHERE jid = ?", arrayOf(jid)).use { c ->
        when {
            !c.moveToFirst() -> false to null
            c.isNull(0) -> true to null
            else -> true to ChatTimer(c.getInt(0), if (c.isNull(1)) 0 else c.getInt(1))
        }
    }

/**
 * Apply the timer a message in 1:1 chat [jid] carried: [update] says what
 * the stored timer (null: not learned yet) becomes (incomingTimerUpdate or
 * syncedTimerUpdate), or null to leave it. A change of a timer Photon knew
 * adds its line to the thread, at [lineTimestamp] (seconds), before the
 * message that carried it. The first timer learned from an ordinary message
 * is stored silently, since nothing visibly changed; an [explicit] timer
 * change (the message says the timer was set) shows its line either way. A
 * chat with no row is left alone. Returns the timer now stored when it
 * changed.
 */
fun SignalMessageDatabase.updateDmTimer(
    jid: String,
    lineTimestamp: Long,
    explicit: Boolean = false,
    update: (ChatTimer?) -> ChatTimer?,
): ChatTimer? {
    val db = writableDatabase
    val updated: ChatTimer
    db.beginTransaction()
    try {
        val (exists, stored) = storedDmTimer(db, jid)
        if (!exists) return null
        updated = update(stored) ?: return null
        db.execSQL(
            "UPDATE conversations SET expire_timer = ?, expire_timer_version = ? WHERE jid = ?",
            arrayOf<Any?>(updated.seconds, updated.version, jid),
        )
        if ((stored != null || explicit) && updated.seconds != stored?.seconds) {
            insertSystemLine(db, jid, lineTimestamp, disappearingMessagesLine(updated.seconds))
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    notifyChanged()
    return updated
}

/**
 * Whether group [jid]'s timer has been read at its latest revision (always
 * true for a chat that isn't a group). A group change bumps the revision,
 * so after one the timer may be stale until the group is fetched again.
 */
fun SignalMessageDatabase.groupTimerCurrent(jid: String): Boolean =
    readableDatabase.rawQuery("SELECT is_group, timer_revision, group_revision FROM conversations WHERE jid = ?", arrayOf(jid))
        .use { !it.moveToFirst() || it.getInt(0) == 0 || (it.getInt(1) >= 0 && it.getInt(1) >= it.getInt(2)) }

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
        ).use { if (it.moveToFirst()) (if (it.isNull(0)) null else it.getInt(0)) to it.getInt(1) else return false }
        if (revision < readAt) return false
        db.execSQL(
            "UPDATE conversations SET expire_timer = ?, expire_timer_version = 1, timer_revision = ? WHERE jid = ?",
            arrayOf<Any?>(seconds, revision, jid),
        )
        if (readAt >= 0 && stored != null && seconds != stored) {
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
 * Group [jid]'s state can't be fetched at all (the server says the account
 * isn't a member, or the group is gone): stop fetching it for its timer
 * until a later revision arrives (a message from it again, say, after the
 * user is added back). Its timer stays as last read, or unknown (a send at
 * version 0) if it never was.
 */
fun SignalMessageDatabase.markGroupStateUnavailable(jid: String) {
    writableDatabase.execSQL(
        "UPDATE conversations SET timer_revision = MAX(group_revision, 0) " +
            "WHERE jid = ? AND is_group = 1 AND (timer_revision < group_revision OR timer_revision < 0)",
        arrayOf(jid),
    )
    notifyChanged()
}

/**
 * The timer the primary's contact sync gives [aci], at [version] (a timer
 * without one is ignored, as it can't be ordered against what messages
 * said). Only a strictly higher version than the one stored applies
 * (contactSyncTimerUpdate), and no line is added. Kept on the contact too,
 * so a chat Photon starts with them later (New message) sends with it from
 * the first message.
 */
fun SignalMessageDatabase.updateTimerFromContactSync(aci: String, seconds: Int, version: Int) {
    if (version <= 0) return
    val db = writableDatabase
    db.beginTransaction()
    try {
        db.execSQL(
            "UPDATE contacts SET expire_timer = ?, expire_timer_version = ? " +
                "WHERE jid = ? AND (expire_timer IS NULL OR expire_timer_version IS NULL OR expire_timer_version < ?)",
            arrayOf<Any?>(seconds, version, aci, version),
        )
        val isDm = db.rawQuery("SELECT 1 FROM conversations WHERE jid = ? AND is_group = 0", arrayOf(aci))
            .use { it.moveToFirst() }
        if (isDm) {
            contactSyncTimerUpdate(storedDmTimer(db, aci).second, seconds, version)?.let { updated ->
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
