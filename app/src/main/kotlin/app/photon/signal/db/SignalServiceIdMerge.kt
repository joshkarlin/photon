package app.photon.signal.db

import android.database.sqlite.SQLiteDatabase
import app.photon.signal.MessageKeys
import app.photon.signal.db.SignalMessageDatabase.Companion.STATUS_UNREAD
import org.signal.core.models.ServiceId

// Conversations stored under a contact's PNI (or bare phone number) before
// their ACI was known, and merging them into the ACI once it is.

/**
 * Non-ACI ids (PNIs, phone numbers) in the contacts table whose phone now
 * maps to an ACI, as (stored id, ACI) pairs.
 */
fun SignalMessageDatabase.getResolvableNonAciIds(): List<Pair<String, String>> {
    val candidates = readableDatabase.rawQuery(
        "SELECT jid, phone_number FROM contacts WHERE phone_number IS NOT NULL AND phone_number != ''",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                val jid = c.getString(0)
                if (ServiceId.ACI.parseOrNull(jid) == null) add(jid to c.getString(1))
            }
        }
    }
    return candidates.mapNotNull { (jid, phone) -> getAciForPhone(phone)?.takeIf { it != jid }?.let { jid to it } }
}

/**
 * Move everything stored under [from] (a PNI, or a phone number used as a
 * conversation key) to the ACI [to], in one transaction:
 *
 *  - Message ids, call row ids, reply keys and reaction keys authored by
 *    [from] are re-keyed to [to]. A message already stored under [to] (the
 *    same message delivered again once the ACI was known) keeps that copy;
 *    the [from] copy is dropped and stops counting as unread.
 *  - The two conversations become one: unread counts add up, the newer last
 *    message wins, and a blank name takes the other's.
 *  - Participant rows move to [to]. The contact row's phone, profile key
 *    and profile name fill gaps in [to]'s; the [from] row stays as an
 *    alias, so a later envelope from [from] that carries no phone number
 *    still maps to [to] through the phone stored there.
 *
 * Incoming rows keep their read state, so rows seen in Photon but never
 * read-synced (impossible without an ACI) are synced on the next chat open.
 * Returns false, changing nothing, when nothing is stored under [from].
 */
fun SignalMessageDatabase.mergeServiceId(from: String, to: String): Boolean {
    if (from == to || from.isBlank() || to.isBlank()) return false
    val db = writableDatabase
    db.beginTransaction()
    try {
        if (!isReferenced(db, from)) return false
        val lostRows = rekeyMessages(db, from, to).map { if (it == from) to else it }
        db.execSQL("UPDATE messages SET sender_jid = ? WHERE sender_jid = ?", arrayOf(to, from))
        db.execSQL("UPDATE messages SET conversation_jid = ? WHERE conversation_jid = ?", arrayOf(to, from))
        rekeyPrefixed(db, "messages", "reply_to_id", from, to)
        // Reactions: keyed by (message key, reactor). A reaction [to] already
        // has for the same message wins over the moved one.
        rekeyPrefixed(db, "reactions", "message_id", from, to, orIgnore = true)
        db.execSQL("DELETE FROM reactions WHERE message_id >= ? AND message_id < ?", rangeOf(from))
        db.execSQL("UPDATE OR IGNORE reactions SET sender_jid = ? WHERE sender_jid = ?", arrayOf(to, from))
        db.execSQL("DELETE FROM reactions WHERE sender_jid = ?", arrayOf(from))
        db.execSQL("UPDATE OR IGNORE participants SET jid = ? WHERE jid = ?", arrayOf(to, from))
        db.execSQL("DELETE FROM participants WHERE jid = ?", arrayOf(from))
        mergeConversationRows(db, from, to)
        // A dropped duplicate may have been a conversation's last message.
        for (jid in (lostRows + to).toSet()) repairLastMessage(db, jid)
        mergeContactRows(db, from, to)
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    notifyChanged()
    return true
}

/** ["{id}_", "{id}_￿"): every key that starts with "{id}_". */
private fun rangeOf(id: String): Array<String> = arrayOf("${id}_", "${id}_￿")

private fun exists(db: SQLiteDatabase, sql: String, args: Array<String>): Boolean =
    db.rawQuery(sql, args).use { it.moveToFirst() }

private fun isReferenced(db: SQLiteDatabase, id: String): Boolean {
    val (lo, hi) = rangeOf(id)
    val (callLo, callHi) = rangeOf("call_$id")
    // The contacts row doesn't count: it stays behind as an alias.
    return exists(db, "SELECT 1 FROM conversations WHERE jid = ?", arrayOf(id)) ||
        exists(db, "SELECT 1 FROM participants WHERE jid = ?", arrayOf(id)) ||
        exists(db, "SELECT 1 FROM reactions WHERE sender_jid = ? OR (message_id >= ? AND message_id < ?)", arrayOf(id, lo, hi)) ||
        exists(
            db,
            "SELECT 1 FROM messages WHERE conversation_jid = ? OR sender_jid = ? " +
                "OR (id >= ? AND id < ?) OR (id >= ? AND id < ?) OR (reply_to_id >= ? AND reply_to_id < ?)",
            arrayOf(id, id, lo, hi, callLo, callHi, lo, hi),
        )
}

/** Replace the leading "{from}_" of [column] with "{to}_". */
private fun rekeyPrefixed(
    db: SQLiteDatabase,
    table: String,
    column: String,
    from: String,
    to: String,
    orIgnore: Boolean = false,
) {
    val (lo, hi) = rangeOf(from)
    db.execSQL(
        "UPDATE ${if (orIgnore) "OR IGNORE " else ""}$table SET $column = ? || substr($column, ?) " +
            "WHERE $column >= ? AND $column < ?",
        arrayOf<Any?>(to, from.length + 1, lo, hi),
    )
}

/**
 * Message rows whose id carries [from]: "{from}_{ts}_{rand}" messages and
 * "call_{from}_{callId}" call rows. Oldest first, so of two copies of one
 * message the first stored is kept. Returns the conversations that lost a
 * dropped copy.
 */
private fun SignalMessageDatabase.rekeyMessages(db: SQLiteDatabase, from: String, to: String): Set<String> {
    val (lo, hi) = rangeOf(from)
    val (callLo, callHi) = rangeOf("call_$from")
    data class Row(val id: String, val conversationJid: String, val unread: Boolean)
    val rows = db.rawQuery(
        "SELECT id, conversation_jid, is_from_me, status FROM messages " +
            "WHERE (id >= ? AND id < ?) OR (id >= ? AND id < ?) ORDER BY rowid",
        arrayOf(lo, hi, callLo, callHi),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(Row(c.getString(0), c.getString(1), c.getInt(2) == 0 && c.getString(3) == STATUS_UNREAD))
        }
    }
    val droppedUnread = mutableMapOf<String, Int>()
    val lostRows = mutableSetOf<String>()
    for (row in rows) {
        val isCallRow = row.id.startsWith("call_$from")
        val newId = if (isCallRow) "call_$to" + row.id.substring("call_$from".length) else to + row.id.substring(from.length)
        val duplicate = if (isCallRow) rowExists(db, newId) else prefixExists(db, MessageKeys.prefixOf(newId))
        if (duplicate) {
            db.execSQL("DELETE FROM messages WHERE id = ?", arrayOf(row.id))
            lostRows += row.conversationJid
            if (row.unread) droppedUnread.merge(row.conversationJid, 1, Int::plus)
        } else {
            db.execSQL("UPDATE messages SET id = ? WHERE id = ?", arrayOf(newId, row.id))
            db.execSQL("UPDATE conversations SET last_message_id = ? WHERE last_message_id = ?", arrayOf(newId, row.id))
        }
    }
    decrementUnread(db, droppedUnread)
    return lostRows
}

private fun mergeConversationRows(db: SQLiteDatabase, from: String, to: String) {
    data class Conv(val name: String?, val unread: Int, val lastId: String?, val lastTs: Long)
    fun load(jid: String) = db.rawQuery(
        "SELECT name, unread_count, last_message_id, last_timestamp FROM conversations WHERE jid = ?",
        arrayOf(jid),
    ).use { c -> if (c.moveToFirst()) Conv(c.getString(0), c.getInt(1), c.getString(2), c.getLong(3)) else null }

    val old = load(from) ?: return
    val target = load(to)
    if (target == null) {
        db.execSQL("UPDATE conversations SET jid = ? WHERE jid = ?", arrayOf(to, from))
    } else {
        val newer = old.lastTs > target.lastTs
        db.execSQL(
            "UPDATE conversations SET unread_count = unread_count + ?, " +
                "name = COALESCE(NULLIF(name, ''), ?), " +
                "last_message_id = CASE WHEN ? THEN ? ELSE last_message_id END, " +
                "last_timestamp = CASE WHEN ? THEN ? ELSE last_timestamp END " +
                "WHERE jid = ?",
            arrayOf<Any?>(old.unread, old.name, if (newer) 1 else 0, old.lastId, if (newer) 1 else 0, old.lastTs, to),
        )
        db.execSQL("DELETE FROM conversations WHERE jid = ?", arrayOf(from))
    }
}

/** Point [jid]'s last_message_id at its newest row if the one it names is gone. */
private fun repairLastMessage(db: SQLiteDatabase, jid: String) {
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

private fun mergeContactRows(db: SQLiteDatabase, from: String, to: String) {
    if (!exists(db, "SELECT 1 FROM contacts WHERE jid = ?", arrayOf(from))) return
    db.execSQL(
        "INSERT OR IGNORE INTO contacts (jid, updated_at) VALUES (?, ?)",
        arrayOf<Any?>(to, System.currentTimeMillis()),
    )
    db.execSQL(
        """
        UPDATE contacts SET
            phone_number = COALESCE(phone_number, (SELECT phone_number FROM contacts WHERE jid = ?)),
            profile_key = COALESCE(profile_key, (SELECT profile_key FROM contacts WHERE jid = ?)),
            profile_name = COALESCE(NULLIF(profile_name, ''), (SELECT profile_name FROM contacts WHERE jid = ?))
        WHERE jid = ?
        """.trimIndent(),
        arrayOf(from, from, from, to),
    )
}
