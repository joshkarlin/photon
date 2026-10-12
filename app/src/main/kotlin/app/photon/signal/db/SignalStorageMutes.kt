package app.photon.signal.db

import android.database.sqlite.SQLiteDatabase
import app.photon.data.db.isMutedAt

// Chat mutes and archive state read from the primary's storage service (see
// StorageMuteSync).
//
// storage_records is the source: one row per contact, GroupV2 or account
// record read, with the conversation it maps to. conversations.muted_until
// always equals what storage_records says for that jid (storageMuteOf), so
// every write that could change either keeps them together:
// applyStorageManifest, a new conversation row (upsertConversation) and a
// PNI merge (mergeServiceId). Encoding, as on WhatsApp: 0 = not muted, -1 =
// muted forever, otherwise the unix second the mute ends.
//
// conversations.archived is the chat's own state: a record that's new to
// Photon sets it (the primary changed that chat), and a new message clears
// it (unarchiveForNewMessage), as Signal does, until the primary's next
// record for that chat says otherwise.

/**
 * A storage record Photon has read. [jid] is the conversation it maps to,
 * or null when it maps to none (it's kept so it isn't read again).
 * [archived]: the chat is archived on the primary. [keepMutedChatsArchived]
 * is only set by the account record (Signal's "Keep muted chats archived"),
 * whose [jid] is Note to Self.
 */
data class StorageMute(
    val storageId: String,
    val jid: String?,
    val mutedUntil: Long,
    val archived: Boolean = false,
    val keepMutedChatsArchived: Boolean = false,
)

/**
 * SQL for the mute the storage records give [jidExpr] (a column or `?`):
 * forever beats any end time, a later end beats an earlier one, and a jid no
 * record maps to is unmuted.
 */
internal fun storageMuteOf(jidExpr: String): String =
    "COALESCE((SELECT CASE WHEN MIN(s.muted_until) = -1 THEN -1 ELSE MAX(s.muted_until) END " +
        "FROM storage_records s WHERE s.jid = $jidExpr), 0)"

/** The manifest version the stored records belong to, or null before the first read. */
fun SignalMessageDatabase.storageManifestVersion(): Long? =
    readableDatabase.rawQuery("SELECT version FROM storage_manifest WHERE id = 1", null)
        .use { if (it.moveToFirst()) it.getLong(0) else null }

/**
 * Read the whole manifest next time, whatever its version: a new storage
 * key may not change the version. The records stay, so mutes hold until
 * then.
 */
fun SignalMessageDatabase.forgetStorageManifestVersion() {
    writableDatabase.execSQL("DELETE FROM storage_manifest")
}

/** The storage ids of every record read so far. */
fun SignalMessageDatabase.storageRecordIds(): Set<String> =
    readableDatabase.rawQuery("SELECT storage_id FROM storage_records", null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0)) }
    }

/**
 * Move to manifest [version], in one transaction: forget the [removed]
 * records, store the [added] ones, and set every conversation's mute from
 * the records that now map to it.
 */
fun SignalMessageDatabase.applyStorageManifest(
    version: Long,
    removed: Collection<String>,
    added: Collection<StorageMute>,
) {
    val db = writableDatabase
    db.beginTransaction()
    try {
        for (id in removed) db.execSQL("DELETE FROM storage_records WHERE storage_id = ?", arrayOf(id))
        for (record in added) {
            db.execSQL(
                "INSERT OR REPLACE INTO storage_records (storage_id, jid, muted_until, archived, keep_muted_archived) " +
                    "VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    record.storageId, record.jid, record.mutedUntil,
                    if (record.archived) 1 else 0, if (record.keepMutedChatsArchived) 1 else 0,
                ),
            )
            record.jid?.let { jid ->
                db.execSQL("UPDATE conversations SET archived = ? WHERE jid = ?", arrayOf<Any?>(if (record.archived) 1 else 0, jid))
            }
        }
        db.execSQL("UPDATE conversations SET muted_until = ${storageMuteOf("conversations.jid")}")
        db.execSQL(
            "INSERT OR REPLACE INTO storage_manifest (id, version) VALUES (1, ?)",
            arrayOf<Any?>(version),
        )
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    notifyChanged()
}

/**
 * SQL for whether the storage records say [jidExpr] (a column or `?`) is
 * archived, for a conversation row created after its record was read.
 */
internal fun storageArchivedOf(jidExpr: String): String =
    "COALESCE((SELECT MAX(s.archived) FROM storage_records s WHERE s.jid = $jidExpr), 0)"

/** Signal's "Keep muted chats archived", from the account record. */
fun SignalMessageDatabase.keepMutedChatsArchived(): Boolean = keepMutedChatsArchived(readableDatabase)

private fun keepMutedChatsArchived(db: SQLiteDatabase): Boolean =
    db.rawQuery("SELECT MAX(keep_muted_archived) FROM storage_records", null)
        .use { it.moveToFirst() && it.getInt(0) == 1 }

/**
 * Whether a new message takes its chat out of the archive. It does, as on
 * Signal (ThreadTable.allowedToUnarchive), unless the chat is muted and the
 * user keeps muted chats archived.
 */
internal fun unarchivesOnNewMessage(muted: Boolean, keepMutedChatsArchived: Boolean): Boolean =
    !(muted && keepMutedChatsArchived)

/** A new message arrived in [jid]: unarchive it if [unarchivesOnNewMessage] says so. In the caller's transaction. */
internal fun unarchiveForNewMessage(db: SQLiteDatabase, jid: String, nowSeconds: Long) {
    val muted = db.rawQuery(
        "SELECT is_muted, muted_until FROM conversations WHERE jid = ? AND archived = 1", arrayOf(jid),
    ).use { c ->
        if (!c.moveToFirst()) return
        isMutedAt(flag = c.getInt(0) == 1, mutedUntil = c.getLong(1), nowSeconds = nowSeconds)
    }
    if (unarchivesOnNewMessage(muted, keepMutedChatsArchived(db))) {
        db.execSQL("UPDATE conversations SET archived = 0 WHERE jid = ?", arrayOf(jid))
    }
}

/** Whether [jid] is muted at [nowSeconds]: a timed mute lapses on its own. */
fun SignalMessageDatabase.isConversationMuted(
    jid: String,
    nowSeconds: Long = System.currentTimeMillis() / 1000,
): Boolean =
    readableDatabase.rawQuery("SELECT is_muted, muted_until FROM conversations WHERE jid = ?", arrayOf(jid)).use { c ->
        c.moveToFirst() && isMutedAt(flag = c.getInt(0) == 1, mutedUntil = c.getLong(1), nowSeconds = nowSeconds)
    }
