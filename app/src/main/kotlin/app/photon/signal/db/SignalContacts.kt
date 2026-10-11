package app.photon.signal.db

import app.photon.data.model.Participant
import app.photon.signal.ConversationNaming
import app.photon.signal.db.SignalMessageDatabase.Contact
import org.signal.core.models.ServiceId

// Contacts (phone, profile key and name per service id) and group participants.

fun SignalMessageDatabase.upsertParticipant(conversationJid: String, jid: String, displayName: String?, role: String = "member") {
    // Preserve any previously known display_name when the caller passes
    // null/blank — repeated upserts with sparse data shouldn't clobber.
    writableDatabase.execSQL(
        """
        INSERT INTO participants (conversation_jid, jid, display_name, role)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(conversation_jid, jid) DO UPDATE SET
            display_name = COALESCE(NULLIF(excluded.display_name, ''), participants.display_name),
            role = excluded.role
        """,
        arrayOf<Any?>(conversationJid, jid, displayName, role),
    )
    notifyChanged()
}

fun SignalMessageDatabase.getParticipants(conversationJid: String): List<Participant> {
    val out = mutableListOf<Participant>()
    readableDatabase.rawQuery(
        "SELECT jid, display_name, role FROM participants WHERE conversation_jid = ?",
        arrayOf(conversationJid),
    ).use { c ->
        while (c.moveToNext()) {
            out.add(
                Participant(
                    conversationJid = conversationJid,
                    jid = c.getString(0),
                    displayName = c.getString(1),
                    role = c.getString(2) ?: "member",
                )
            )
        }
    }
    return out
}

fun SignalMessageDatabase.updateContactPhone(jid: String, phone: String) {
    writableDatabase.execSQL(
        """
        INSERT INTO contacts (jid, phone_number, updated_at) VALUES (?, ?, ?)
        ON CONFLICT(jid) DO UPDATE SET phone_number = excluded.phone_number
        """,
        arrayOf<Any?>(jid, phone, System.currentTimeMillis()),
    )
    notifyChanged()
}

fun SignalMessageDatabase.updateContactProfileKey(jid: String, profileKey: ByteArray) {
    writableDatabase.execSQL(
        """
        INSERT INTO contacts (jid, profile_key, updated_at) VALUES (?, ?, ?)
        ON CONFLICT(jid) DO UPDATE SET profile_key = excluded.profile_key
        """,
        arrayOf<Any?>(jid, profileKey, System.currentTimeMillis()),
    )
}

fun SignalMessageDatabase.updateContactProfileName(jid: String, profileName: String?) {
    writableDatabase.execSQL(
        """
        INSERT INTO contacts (jid, profile_name, profile_fetched_at, updated_at) VALUES (?, ?, ?, ?)
        ON CONFLICT(jid) DO UPDATE SET
            profile_name = excluded.profile_name,
            profile_fetched_at = excluded.profile_fetched_at
        """,
        arrayOf<Any?>(jid, profileName, System.currentTimeMillis(), System.currentTimeMillis()),
    )
    notifyChanged()
}

fun SignalMessageDatabase.getContact(jid: String): Contact? {
    return readableDatabase.rawQuery(
        "SELECT jid, name, phone_number, profile_key, profile_name, profile_fetched_at FROM contacts WHERE jid = ?",
        arrayOf(jid),
    ).use { c ->
        if (c.moveToFirst()) {
            Contact(
                jid = c.getString(0),
                name = if (c.isNull(1)) null else c.getString(1),
                phone = if (c.isNull(2)) null else c.getString(2),
                profileKey = if (c.isNull(3)) null else c.getBlob(3),
                profileName = if (c.isNull(4)) null else c.getString(4),
                profileFetchedAt = c.getLong(5),
            )
        } else null
    }
}

/**
 * Resolve a phone number to the canonical ACI row learned from contacts
 * sync. PNI rows can also carry the same phone number after a PNI-sourced
 * envelope, but conversations must key by ACI so replies route through the
 * sender path.
 */
fun SignalMessageDatabase.getAciForPhone(phone: String): String? {
    return readableDatabase.rawQuery(
        "SELECT jid FROM contacts WHERE phone_number = ?",
        arrayOf(phone),
    ).use { c ->
        while (c.moveToNext()) {
            val jid = c.getString(0)
            if (ServiceId.ACI.parseOrNull(jid) != null) return@use jid
        }
        null
    }
}

/**
 * Display-name policy for a group member, shared by receiver and
 * sender so the participants table stays consistent. Same order as
 * conversation titles: local contact (when the caller supplies a phone
 * resolver) → Signal profile name → bare phone → ACI prefix.
 */
fun SignalMessageDatabase.memberDisplayName(aci: String, contactResolver: ((String) -> String?)? = null): String {
    val contact = getContact(aci)
    return ConversationNaming.resolveFromContact(
        phone = contact?.phone,
        profileName = contact?.profileName,
        resolveByPhone = { phone -> contactResolver?.invoke(phone) },
    ) ?: aci.take(8)
}

/**
 * All contacts whose profile we know how to fetch (have profile_key)
 * but for which the encrypted name hasn't been decoded yet. Used by the
 * receiver to retry failed profile fetches on each WS (re)connect.
 */
fun SignalMessageDatabase.getContactsWithProfileKeyButNoName(): List<Contact> {
    return readableDatabase.rawQuery(
        "SELECT jid, name, phone_number, profile_key, profile_name, profile_fetched_at " +
            "FROM contacts WHERE profile_key IS NOT NULL " +
            "AND (profile_name IS NULL OR profile_name = '')",
        null,
    ).use { c ->
        val out = mutableListOf<Contact>()
        while (c.moveToNext()) {
            out.add(
                Contact(
                    jid = c.getString(0),
                    name = if (c.isNull(1)) null else c.getString(1),
                    phone = if (c.isNull(2)) null else c.getString(2),
                    profileKey = if (c.isNull(3)) null else c.getBlob(3),
                    profileName = if (c.isNull(4)) null else c.getString(4),
                    profileFetchedAt = c.getLong(5),
                ),
            )
        }
        out
    }
}
