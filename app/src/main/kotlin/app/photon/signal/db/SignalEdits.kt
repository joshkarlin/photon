package app.photon.signal.db

import app.photon.data.model.ContentTypes
import app.photon.signal.MessageKeys
import app.photon.signal.db.SignalMessageDatabase.Companion.CONTENT_CALL
import app.photon.signal.db.SignalMessageDatabase.Companion.STATUS_DELETED
import app.photon.signal.db.SignalMessageDatabase.Companion.STATUS_UNREAD

// Edits: a message's text replaced in place, keeping its row and position.
//
// Every edit is a message of its own, sent at its own timestamp, naming the
// message it edits by that message's sent timestamp. Signal-Android names
// the latest version (so a second edit names the first edit's timestamp),
// and later reactions, deletes and read syncs can name an edit too. So each
// applied edit is remembered in message_aliases: the edit's
// "{author}_{timestamp}" key, pointing at the row it changed.

/**
 * The "{author}_{timestamp}" prefix of the stored message [prefix] names:
 * [prefix] itself when it's stored, else the message an edit sent under
 * that key changed, else [prefix] unchanged.
 */
fun SignalMessageDatabase.resolveMessagePrefix(prefix: String): String {
    val db = readableDatabase
    if (prefixExists(db, prefix)) return prefix
    return db.rawQuery("SELECT target_id FROM message_aliases WHERE alias = ?", arrayOf(prefix))
        .use { if (it.moveToFirst()) MessageKeys.prefixOf(it.getString(0)) else prefix }
}

/** An applied edit: [rowId] in [conversationJid] now shows the new text; [unread] if it hasn't been read yet. */
data class AppliedEdit(val conversationJid: String, val rowId: String, val unread: Boolean)

/**
 * Apply an edit, sent as [editPrefix], to the message keyed [targetPrefix]
 * (already resolved through [resolveMessagePrefix]) in [conversationJid]:
 * the row holding its text (a text row, or the photo or video carrying the
 * caption) takes [body] and counts one more edit. A document-only message
 * edited to say something gets a text row after its documents.
 *
 * As Signal-Android, an edit can't change a view-once message, a voice
 * note, a shared contact or a deleted message, and must come from the same
 * chat; the author is checked by the key, which includes them. Returns null,
 * changing nothing, for an edit that doesn't apply or whose message isn't
 * stored.
 */
fun SignalMessageDatabase.applyEdit(
    targetPrefix: String,
    editPrefix: String,
    conversationJid: String,
    body: String?,
): AppliedEdit? {
    data class Row(val id: String, val jid: String, val type: String, val status: String, val fromMe: Boolean)
    val db = writableDatabase
    val applied: AppliedEdit
    db.beginTransaction()
    try {
        val rows = db.rawQuery(
            "SELECT id, conversation_jid, content_type, status, is_from_me FROM messages " +
                "WHERE id >= ? AND id < ? ORDER BY rowid",
            arrayOf("${targetPrefix}_", "${targetPrefix}_￿"),
        ).use { c ->
            buildList { while (c.moveToNext()) add(Row(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) == 1)) }
        }
        val first = rows.firstOrNull() ?: return null
        val uneditable = setOf(ContentTypes.VIEW_ONCE, ContentTypes.SYSTEM, "audio", "contact", CONTENT_CALL)
        if (first.jid != conversationJid || first.status == STATUS_DELETED || rows.any { it.type in uneditable }) return null

        val textRow = rows.firstOrNull { it.type == "text" } ?: rows.firstOrNull { it.type == "image" || it.type == "video" }
        val edited = when {
            textRow != null -> {
                if (textRow.type == "text" && body.isNullOrBlank()) return null
                db.execSQL(
                    "UPDATE messages SET text_body = ?, edit_version = edit_version + 1 WHERE id = ?",
                    arrayOf<Any?>(body?.takeIf { it.isNotBlank() }, textRow.id),
                )
                textRow
            }
            body.isNullOrBlank() -> return null
            else -> {
                // Documents only: the new text gets a row of its own, read or
                // not as the message is.
                val id = MessageKeys.partId(first.id, rows.size)
                db.execSQL(
                    "INSERT INTO messages (id, conversation_jid, sender_jid, timestamp, content_type, text_body, " +
                        "edit_version, is_from_me, status, expires_in, expires_at) " +
                        "SELECT ?, conversation_jid, sender_jid, timestamp, 'text', ?, 1, is_from_me, status, expires_in, expires_at " +
                        "FROM messages WHERE id = ?",
                    arrayOf<Any?>(id, body, first.id),
                )
                if (!first.fromMe && first.status == STATUS_UNREAD) adjustUnread(db, conversationJid, +1)
                Row(id, first.jid, "text", first.status, first.fromMe)
            }
        }
        db.execSQL(
            "INSERT OR REPLACE INTO message_aliases (alias, target_id) VALUES (?, ?)",
            arrayOf<Any?>(editPrefix, first.id),
        )
        applied = AppliedEdit(conversationJid, edited.id, !edited.fromMe && edited.status == STATUS_UNREAD)
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    notifyChanged()
    return applied
}
