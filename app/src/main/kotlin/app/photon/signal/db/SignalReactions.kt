package app.photon.signal.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.photon.data.db.toReaction
import app.photon.data.model.Reaction
import app.photon.signal.MessageKeys

// Reactions, keyed by the target message's "{author}_{timestampMs}" prefix (see MessageKeys).

fun SignalMessageDatabase.upsertReaction(messageId: String, senderJid: String, emoji: String, timestamp: Long) {
    writableDatabase.insertWithOnConflict(
        "reactions", null,
        ContentValues().apply {
            put("message_id", messageId)
            put("sender_jid", senderJid)
            put("emoji", emoji)
            put("timestamp", timestamp)
        },
        SQLiteDatabase.CONFLICT_REPLACE,
    )
    notifyChanged()
}

/** A sender withdrew their reaction (Signal's `remove` flag). */
fun SignalMessageDatabase.removeReaction(messageId: String, senderJid: String) {
    writableDatabase.execSQL(
        "DELETE FROM reactions WHERE message_id = ? AND sender_jid = ?",
        arrayOf(messageId, senderJid),
    )
    notifyChanged()
}

fun SignalMessageDatabase.getReactions(messageIds: List<String>): Map<String, List<Reaction>> {
    if (messageIds.isEmpty()) return emptyMap()
    // Reactions are stored under the suffix-less "{author}_{timestampMs}"
    // prefix, while message ids carry an extra "_{rand}". Query by prefix and
    // map results back onto the full ids the caller indexes by, so incoming
    // reactions actually attach to their message. (See MessageKeys.)
    val prefixes = messageIds.map { MessageKeys.prefixOf(it) }.distinct()
    val placeholders = prefixes.joinToString(",") { "?" }
    return readableDatabase.rawQuery(
        "SELECT * FROM reactions WHERE message_id IN ($placeholders)",
        prefixes.toTypedArray(),
    ).use { c ->
        val byPrefix = mutableMapOf<String, MutableList<Reaction>>()
        while (c.moveToNext()) {
            val r = c.toReaction()
            byPrefix.getOrPut(r.messageId) { mutableListOf() }.add(r)
        }
        MessageKeys.remapByPrefix(messageIds, byPrefix)
    }
}
