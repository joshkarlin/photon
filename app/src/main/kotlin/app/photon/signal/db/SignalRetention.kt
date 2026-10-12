package app.photon.signal.db

import app.photon.signal.MessageKeys
import app.photon.signal.db.SignalMessageDatabase.Companion.STATUS_SEEN
import app.photon.signal.db.SignalMessageDatabase.Companion.STATUS_UNREAD

// The ephemeral message window.

/**
 * Enforce the ephemeral window (README: N messages per chat or D days).
 * Returns the number of rows removed. 0 disables a limit.
 *
 * The per-conversation cap applies to every row, so storage stays bounded
 * even for chats that are never opened. The age limit skips rows that are
 * still unread — a message nobody has seen shouldn't vanish just because
 * the phone was off for a week — and seen rows whose read sync hasn't gone
 * out yet (the cap still bounds both). Pruned unread rows are subtracted
 * from unread_count. Conversation rows are kept — they carry names, group
 * master keys and members.
 */
fun SignalMessageDatabase.pruneMessages(
    maxPerConversation: Int,
    maxAgeDays: Int,
    nowSeconds: Long = System.currentTimeMillis() / 1000,
): Int {
    val db = writableDatabase
    // id → (conversation, counted as unread)
    val doomed = linkedMapOf<String, Pair<String, Boolean>>()
    db.beginTransaction()
    try {
        if (maxAgeDays > 0) {
            val cutoff = nowSeconds - maxAgeDays * 86_400L
            db.rawQuery(
                "SELECT id, conversation_jid FROM messages " +
                    "WHERE timestamp < ? AND NOT (is_from_me = 0 AND status IN (?, ?))",
                arrayOf(cutoff.toString(), STATUS_UNREAD, STATUS_SEEN),
            ).use { c -> while (c.moveToNext()) doomed[c.getString(0)] = c.getString(1) to false }
        }
        if (maxPerConversation > 0) {
            val jids = db.rawQuery("SELECT DISTINCT conversation_jid FROM messages", null)
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            for (jid in jids) {
                db.rawQuery(
                    "SELECT id, is_from_me, status FROM messages WHERE conversation_jid = ? " +
                        "ORDER BY timestamp DESC, rowid DESC LIMIT -1 OFFSET ?",
                    arrayOf(jid, maxPerConversation.toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        val unread = c.getInt(1) == 0 && c.getString(2) == STATUS_UNREAD
                        doomed[c.getString(0)] = jid to unread
                    }
                }
            }
        }
        if (doomed.isEmpty()) return 0

        for (chunk in doomed.keys.chunked(500)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db.execSQL("DELETE FROM messages WHERE id IN ($placeholders)", chunk.toTypedArray())
        }
        // Reactions are keyed by the "{author}_{ts}" prefix; a message stored
        // as several rows keeps them while any of its rows is left.
        val gonePrefixes = doomed.keys.map { MessageKeys.prefixOf(it) }.distinct().filterNot { prefixExists(db, it) }
        for (chunk in gonePrefixes.chunked(500)) {
            db.execSQL(
                "DELETE FROM reactions WHERE message_id IN (${chunk.joinToString(",") { "?" }})",
                chunk.toTypedArray(),
            )
        }
        db.execSQL("DELETE FROM message_aliases WHERE target_id NOT IN (SELECT id FROM messages)")
        val unreadPruned = doomed.values.filter { it.second }.groupingBy { it.first }.eachCount()
        decrementUnread(db, unreadPruned)
        // Keep last_message_id pointing at a row that still exists.
        for (jid in doomed.values.map { it.first }.distinct()) {
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
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    notifyChanged()
    return doomed.size
}
