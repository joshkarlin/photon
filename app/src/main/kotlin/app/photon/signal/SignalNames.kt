package app.photon.signal

import app.photon.data.model.Conversation
import app.photon.data.model.Platform
import app.photon.data.names.DisplayNames
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.SignalMessageDatabase.Contact
import app.photon.signal.db.getContact
import app.photon.signal.db.getConversation

/**
 * Signal names, resolved when read from what the database stores about each
 * person ([DisplayNames]' policy): the LP3 contact for the number contact
 * sync (or an envelope) gave their ACI, the primary's address-book name from
 * contact sync, their Signal profile name, then the number. Nothing resolved
 * is stored, so a newly learned source or an LP3 edit shows on the next read.
 * Groups show the subject fetched from the server.
 *
 * @param lp3 the LP3 contact name for a number (LocalContacts)
 * @param selfAci the account's own ACI, whose DM is "Note to Self"
 */
class SignalNames(
    private val db: SignalMessageDatabase,
    private val lp3: (String) -> String?,
    private val selfAci: () -> String?,
) {
    /** A person, by ACI (or a PNI / number while their ACI is unknown). */
    fun person(serviceId: String): String = person(serviceId, db.getContact(serviceId))

    /** [person] with their contacts row already in hand. */
    fun person(serviceId: String, contact: Contact?): String {
        val phone = contact?.phone?.takeIf { it.isNotBlank() }
            ?: serviceId.takeIf { DisplayNames.looksLikePhone(it) }
        return DisplayNames.person(
            Platform.SIGNAL,
            lp3 = phone?.let(lp3),
            addressBook = contact?.name,
            profile = contact?.profileName,
            phone = phone,
        )
    }

    /** A conversation's title: the group's subject, "Note to Self", or the person. */
    fun title(conversation: Conversation, contact: Contact?): String = when {
        conversation.isGroup -> DisplayNames.group(Platform.SIGNAL, conversation.name, conversation.jid)
        conversation.jid == selfAci() -> DisplayNames.NOTE_TO_SELF
        else -> person(conversation.jid, contact)
    }

    /** [title] by id, for notifications. */
    fun title(conversationJid: String, isGroup: Boolean): String = when {
        isGroup -> DisplayNames.group(Platform.SIGNAL, db.getConversation(conversationJid)?.name, conversationJid)
        conversationJid == selfAci() -> DisplayNames.NOTE_TO_SELF
        else -> person(conversationJid)
    }

    /** [conversation] with its name resolved. */
    fun resolve(conversation: Conversation, contact: Contact?): Conversation =
        conversation.copy(name = title(conversation, contact))
}
