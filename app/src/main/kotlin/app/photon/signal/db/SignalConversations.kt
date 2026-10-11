package app.photon.signal.db

import app.photon.data.db.toConversation
import app.photon.data.db.toConversations
import app.photon.data.model.Conversation
import app.photon.signal.ConversationNaming
import app.photon.signal.db.SignalMessageDatabase.GroupMeta

// Conversation rows: names, last message, GroupV2 metadata.

fun SignalMessageDatabase.upsertConversation(
    jid: String,
    name: String?,
    isGroup: Boolean,
    lastMessageId: String? = null,
    lastTimestamp: Long = 0,
) {
    // Hand-rolled UPSERT so that callers who only know name/jid (e.g. the
    // contacts-sync ingest) don't clobber the existing last_message_id or
    // last_timestamp on a conversation that already has message history.
    // INSERT plants defaults on new rows; UPDATE only writes the fields
    // the caller actually supplied.
    writableDatabase.execSQL(
        """
        INSERT INTO conversations (jid, name, is_group, last_message_id, last_timestamp, updated_at)
          VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(jid) DO UPDATE SET
          name           = COALESCE(excluded.name, conversations.name),
          is_group       = excluded.is_group,
          last_message_id = COALESCE(NULLIF(excluded.last_message_id, ''), conversations.last_message_id),
          last_timestamp  = CASE WHEN excluded.last_timestamp > 0
                                 THEN excluded.last_timestamp
                                 ELSE conversations.last_timestamp END,
          updated_at      = excluded.updated_at
        """,
        arrayOf<Any?>(
            jid,
            name,
            if (isGroup) 1 else 0,
            lastMessageId ?: "",
            lastTimestamp,
            System.currentTimeMillis(),
        ),
    )
    notifyChanged()
}

/**
 * Point the conversation at a newly stored message, unless it already
 * shows a newer one: a late sync transcript must not drag the chat back
 * in the list.
 */
fun SignalMessageDatabase.updateConversationLastMessage(jid: String, messageId: String, timestamp: Long) {
    setLastMessageIfNewer(writableDatabase, jid, messageId, timestamp)
    notifyChanged()
}

/**
 * Persist the GroupV2 metadata on a conversation row. We need the
 * master_key to (re)fetch group state from the server; the revision
 * lets us skip refetches when nothing has changed since we last looked.
 * Safe to call repeatedly with the same values — no-op via UPDATE.
 */
fun SignalMessageDatabase.updateGroupMeta(jid: String, masterKey: ByteArray, revision: Int) {
    writableDatabase.execSQL(
        "UPDATE conversations SET master_key = ?, group_revision = ? WHERE jid = ?",
        arrayOf<Any?>(masterKey, revision, jid),
    )
    notifyChanged()
}

fun SignalMessageDatabase.getGroupMeta(jid: String): GroupMeta? {
    readableDatabase.rawQuery(
        "SELECT master_key, group_revision FROM conversations WHERE jid = ?",
        arrayOf(jid),
    ).use { c ->
        if (!c.moveToFirst()) return null
        val mk = c.getBlob(0) ?: return null
        val rev = c.getInt(1)
        return GroupMeta(mk, rev)
    }
}

fun SignalMessageDatabase.getConversations(): List<Conversation> {
    return readableDatabase.rawQuery(
        "SELECT * FROM conversations ORDER BY last_timestamp DESC", null,
    ).use { it.toConversations() }
}

fun SignalMessageDatabase.getConversation(jid: String): Conversation? {
    return readableDatabase.rawQuery(
        "SELECT * FROM conversations WHERE jid = ?", arrayOf(jid),
    ).use { if (it.moveToFirst()) it.toConversation() else null }
}

/**
 * For each existing conversation, look at the row's stored phone (from
 * the contacts table) and reset `name` to whatever the supplied resolver
 * returns. Used to re-derive conversation titles from local contacts
 * when the resolution policy changes. Conversations with no phone are
 * left alone.
 */
fun SignalMessageDatabase.reresolveConversationNames(resolveByPhone: (String) -> String?): Int {
    var updated = 0
    readableDatabase.rawQuery(
        "SELECT c.jid, c.name, ct.phone_number, ct.profile_name FROM conversations c " +
            "LEFT JOIN contacts ct ON ct.jid = c.jid",
        null,
    ).use { cur ->
        while (cur.moveToNext()) {
            val jid = cur.getString(0)
            val currentName = if (cur.isNull(1)) null else cur.getString(1)
            val phone = if (cur.isNull(2)) null else cur.getString(2)
            val profileName = if (cur.isNull(3)) null else cur.getString(3)
            // Resolution priority: local LP3 contact → Signal profile
            // name (when the fetcher could decrypt one) → phone number.
            val resolved = ConversationNaming.resolveFromContact(phone, profileName, resolveByPhone)
            // Only overwrite when we actually resolved a name. A null result
            // means "no phone/profile" (every group, plus DMs we can't name
            // yet) — nulling those wiped group titles, which then showed as a
            // base64 JID in the UI.
            if (resolved != null && resolved != currentName) {
                writableDatabase.execSQL(
                    "UPDATE conversations SET name = ? WHERE jid = ?",
                    arrayOf<Any?>(resolved, jid),
                )
                updated++
            }
        }
    }
    if (updated > 0) notifyChanged()
    return updated
}

/**
 * Groups whose server metadata should be refreshed after reconnect: either
 * the title is blank or the member table is empty. The latter matters for
 * linked-device session priming, because group-only contacts are discovered
 * through GroupV2 member state before they can be NullMessage-pinged.
 */
fun SignalMessageDatabase.getGroupsNeedingMetadataRefresh(): List<Pair<String, ByteArray>> {
    val out = mutableListOf<Pair<String, ByteArray>>()
    readableDatabase.rawQuery(
        """
        SELECT c.jid, c.master_key
          FROM conversations c
          WHERE c.is_group = 1
            AND c.master_key IS NOT NULL
            AND (
                c.name IS NULL OR c.name = ''
                OR NOT EXISTS (
                    SELECT 1 FROM participants p WHERE p.conversation_jid = c.jid
                )
            )
        """,
        null,
    ).use { c ->
        while (c.moveToNext()) {
            val jid = c.getString(0)
            val mk = if (c.isNull(1)) null else c.getBlob(1)
            if (mk != null && mk.size == 32) out.add(jid to mk)
        }
    }
    return out
}

/**
 * ACIs that should receive a silent NullMessage after connect so their
 * clients learn this linked deviceId and include Photon in future
 * encrypted recipient lists.
 *
 * DM conversation JIDs cover contacts we've exchanged direct messages
 * with. GroupV2 participant rows cover group-only contacts; without this
 * branch, Photon could send into a group but never receive other members'
 * messages because their clients had no session for this linked device.
 */
fun SignalMessageDatabase.getSessionPingTargetAcis(): List<String> {
    return readableDatabase.rawQuery(
        """
        SELECT DISTINCT jid FROM (
            SELECT m.conversation_jid AS jid
              FROM messages m
              LEFT JOIN conversations c ON c.jid = m.conversation_jid
              WHERE COALESCE(c.is_group, 0) = 0
            UNION
            SELECT p.jid AS jid
              FROM participants p
              INNER JOIN conversations c ON c.jid = p.conversation_jid
              WHERE COALESCE(c.is_group, 0) = 1
        )
        WHERE jid IS NOT NULL AND jid != ''
        ORDER BY jid
        """,
        null,
    ).use { c ->
        val out = mutableListOf<String>()
        while (c.moveToNext()) out.add(c.getString(0))
        out
    }
}
