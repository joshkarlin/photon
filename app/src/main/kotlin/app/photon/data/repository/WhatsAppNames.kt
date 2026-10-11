package app.photon.data.repository

import app.photon.data.model.BridgeEvent
import app.photon.data.model.Conversation
import app.photon.data.model.Participant
import app.photon.data.model.Platform
import app.photon.data.model.boolean
import app.photon.data.model.string
import app.photon.data.names.DisplayNames
import app.photon.service.MessageNotificationText
import app.photon.service.messageNotificationText
import app.photon.service.notificationPreview

/**
 * WhatsApp names: the LP3 overlay on what the bridge stores. The bridge keeps
 * the best name WhatsApp itself gives a person (address book, then business,
 * then push name) in conversations.name and participants.display_name, or ""
 * — never a number. Photon puts the LP3 contact above it for anyone whose
 * number it knows. An @lid-only person has no number, so skips that tier.
 *
 * @param lp3 the LP3 contact name for a number (LocalContacts)
 */
class WhatsAppNames(private val lp3: (String) -> String?) {

    /** A person, from their JID and the name the bridge stored for them. */
    fun person(jid: String, bridgeName: String?): String {
        val phone = DisplayNames.phoneOf(jid, Platform.WHATSAPP)
        return DisplayNames.person(Platform.WHATSAPP, lp3 = phone?.let(lp3), addressBook = bridgeName, phone = phone)
    }

    /** A chat's title, from its stored row. */
    fun title(jid: String, isGroup: Boolean, storedName: String?): String =
        if (isGroup) DisplayNames.group(Platform.WHATSAPP, storedName, jid) else person(jid, storedName)

    /** [conversation] with its name resolved. */
    fun resolve(conversation: Conversation): Conversation =
        conversation.copy(name = title(conversation.jid, conversation.isGroup, conversation.name))

    /**
     * A group's sender names by JID: its members, plus anyone who has written
     * in it without being stored as one.
     */
    fun participantNames(participants: List<Participant>, senders: Collection<String>): Map<String, String> {
        val stored = participants.associate { it.jid to it.displayName }
        return (stored.keys + senders).associateWith { person(it, stored[it]) }
    }

    /**
     * The notification for a bridge `new_message` event. The title comes from
     * conversation_name (the chat's own name), the group sender from
     * sender_jid and sender_name (the sender's own name). [stored] is the
     * conversation row as the bridge wrote it, the fallback for an empty
     * conversation_name and for bridges that predate these fields.
     */
    fun messageNotification(evt: BridgeEvent, stored: Conversation?): MessageNotificationText {
        val conversationJid = evt.string("conversation_jid").orEmpty()
        val preview = notificationPreview(evt.string("text_body"), evt.string("content_type"))
        val senderName = evt.string("sender_name")?.takeIf { it.isNotBlank() }
        // Older bridges send no is_group, and their sender_name is the chat's
        // name (a group's subject), with nothing about the sender.
        val legacy = evt.boolean("is_group") == null
        val isGroup = evt.boolean("is_group") ?: stored?.isGroup ?: conversationJid.endsWith("@g.us")
        val chatName = if (legacy) {
            stored?.name?.takeIf { it.isNotBlank() } ?: senderName
        } else {
            evt.string("conversation_name")?.takeIf { it.isNotBlank() } ?: stored?.name
        }
        val title = title(conversationJid, isGroup, chatName)
        val sender = if (isGroup && !legacy) {
            evt.string("sender_jid")?.takeIf { it.isNotBlank() }?.let { person(it, senderName) }
                ?: senderName?.let { DisplayNames.person(Platform.WHATSAPP, addressBook = it) }
        } else {
            null
        }
        return messageNotificationText(title, isGroup, sender, preview)
    }

    /**
     * Who a `call_incoming` / `call_missed` notification names: the caller
     * (caller_jid and their own caller_name), and for a group call the group
     * too ("Alice · Climbing"). Older bridges send no caller_jid; their
     * caller_name is the chat's name, or a number or placeholder.
     */
    fun callerTitle(evt: BridgeEvent, stored: Conversation?): String {
        val conversationJid = evt.string("conversation_jid").orEmpty()
        val isGroup = evt.boolean("is_group") ?: stored?.isGroup ?: conversationJid.endsWith("@g.us")
        val callerName = evt.string("caller_name")?.takeIf { it.isNotBlank() }
        val callerJid = evt.string("caller_jid")?.takeIf { it.isNotBlank() }
        if (callerJid == null) return title(conversationJid, isGroup, callerName ?: stored?.name)
        val caller = person(callerJid, callerName)
        if (!isGroup) return caller
        val chatName = evt.string("conversation_name")?.takeIf { it.isNotBlank() } ?: stored?.name
        return "$caller · ${DisplayNames.group(Platform.WHATSAPP, chatName, conversationJid)}"
    }
}
