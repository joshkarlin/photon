package app.photon.signal.db

import app.photon.data.model.Participant
import app.photon.signal.db.SignalMessageDatabase.Contact
import org.signal.core.models.ServiceId

// Contacts (phone, names and profile key per service id) and group members.

/**
 * Record [jid] as a member of a group. Only membership: their name is
 * resolved from the contacts table when read (SignalNames), so
 * participants.display_name is no longer written.
 */
fun SignalMessageDatabase.upsertParticipant(conversationJid: String, jid: String, role: String = "member") {
    writableDatabase.execSQL(
        """
        INSERT INTO participants (conversation_jid, jid, role) VALUES (?, ?, ?)
        ON CONFLICT(conversation_jid, jid) DO UPDATE SET role = excluded.role
        """,
        arrayOf<Any?>(conversationJid, jid, role),
    )
    notifyChanged()
}

/**
 * The people of a group, with their name sources: its members, plus anyone
 * who has written in it without being stored as one.
 */
fun SignalMessageDatabase.getGroupPeople(conversationJid: String): List<Pair<String, Contact?>> =
    readableDatabase.rawQuery(
        """
        SELECT p.jid, ct.jid, ct.name, ct.phone_number, ct.profile_name FROM (
            SELECT jid FROM participants WHERE conversation_jid = ?
            UNION
            SELECT sender_jid AS jid FROM messages WHERE conversation_jid = ? AND is_from_me = 0
        ) p LEFT JOIN contacts ct ON ct.jid = p.jid
        """,
        arrayOf(conversationJid, conversationJid),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(c.getString(0) to c.nameSources(1))
        }
    }

/**
 * The name sources of a contacts row read from [first] on (jid, name,
 * phone_number, profile_name), or null when the LEFT JOIN found none.
 */
internal fun android.database.Cursor.nameSources(first: Int): Contact? {
    if (isNull(first)) return null
    fun str(i: Int) = if (isNull(i)) null else getString(i)
    return Contact(
        jid = getString(first),
        name = str(first + 1),
        phone = str(first + 2),
        profileKey = null,
        profileName = str(first + 3),
        profileFetchedAt = 0,
    )
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

/**
 * A contact as the primary's contact sync describes it. The sync carries the
 * whole current entry, so its address-book [name] replaces the stored one
 * (null: no longer named there). A missing [phone] keeps the stored one,
 * which an envelope may have supplied.
 */
fun SignalMessageDatabase.updateContactFromSync(jid: String, phone: String?, name: String?) {
    writableDatabase.execSQL(
        """
        INSERT INTO contacts (jid, name, phone_number, updated_at) VALUES (?, ?, ?, ?)
        ON CONFLICT(jid) DO UPDATE SET
            name = excluded.name,
            phone_number = COALESCE(excluded.phone_number, contacts.phone_number)
        """,
        arrayOf<Any?>(jid, name?.trim()?.takeIf { it.isNotEmpty() }, phone, System.currentTimeMillis()),
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

fun SignalMessageDatabase.updateContactProfileName(jid: String, profileName: String) {
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
