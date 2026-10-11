package app.photon.signal.db

import app.photon.data.db.isMutedAt

// Chat mutes read from the primary's storage service (see StorageMuteSync).
//
// storage_records is the source: one row per contact or GroupV2 record read,
// with the conversation it maps to. conversations.muted_until always equals
// what storage_records says for that jid (storageMuteOf), so every write
// that could change either keeps them together: applyStorageManifest, a new
// conversation row (upsertConversation) and a PNI merge (mergeServiceId).
// Encoding, as on WhatsApp: 0 = not muted, -1 = muted forever, otherwise the
// unix second the mute ends.

/**
 * A storage record Photon has read. [jid] is the conversation it maps to,
 * or null when it maps to none (it's kept so it isn't read again).
 */
data class StorageMute(val storageId: String, val jid: String?, val mutedUntil: Long)

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
                "INSERT OR REPLACE INTO storage_records (storage_id, jid, muted_until) VALUES (?, ?, ?)",
                arrayOf<Any?>(record.storageId, record.jid, record.mutedUntil),
            )
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

/** Whether [jid] is muted at [nowSeconds]: a timed mute lapses on its own. */
fun SignalMessageDatabase.isConversationMuted(
    jid: String,
    nowSeconds: Long = System.currentTimeMillis() / 1000,
): Boolean =
    readableDatabase.rawQuery("SELECT is_muted, muted_until FROM conversations WHERE jid = ?", arrayOf(jid)).use { c ->
        c.moveToFirst() && isMutedAt(flag = c.getInt(0) == 1, mutedUntil = c.getLong(1), nowSeconds = nowSeconds)
    }
