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
     * The notification for a bridge `new_message` event. [stored] is the
     * conversation row as the bridge wrote it, for bridges that predate the
     * event's naming fields (is_group, conversation_name, sender_jid).
     */
    fun messageNotification(evt: BridgeEvent, stored: Conversation?): MessageNotificationText {
        val conversationJid = evt.string("conversation_jid").orEmpty()
        val preview = notificationPreview(evt.string("text_body"), evt.string("content_type"))
        val eventIsGroup = evt.boolean("is_group")
        val isGroup = eventIsGroup ?: stored?.isGroup ?: conversationJid.endsWith("@g.us")
        val senderName = evt.string("sender_name")?.takeIf { it.isNotBlank() }
        val conversationName = evt.string("conversation_name")?.takeIf { it.isNotBlank() }

        if (!isGroup) {
            val title = person(conversationJid, conversationName ?: stored?.name ?: senderName)
            return messageNotificationText(title, isGroup = false, senderName = null, preview = preview)
        }
        if (eventIsGroup == null) {
            // An older bridge: its sender_name for a group is the group's name,
            // and it says nothing of the sender.
            val title = DisplayNames.group(Platform.WHATSAPP, senderName ?: stored?.name, conversationJid)
            return messageNotificationText(title, isGroup = true, senderName = null, preview = preview)
        }
        val title = DisplayNames.group(Platform.WHATSAPP, conversationName ?: stored?.name, conversationJid)
        val senderJid = evt.string("sender_jid")?.takeIf { it.isNotBlank() }
        val sender = when {
            senderJid != null -> person(senderJid, senderName)
            senderName != null -> DisplayNames.person(Platform.WHATSAPP, addressBook = senderName)
            else -> null
        }
        return messageNotificationText(title, isGroup = true, senderName = sender, preview = preview)
    }

    /**
     * Who a `call_incoming` / `call_missed` notification names: the group for
     * a group call, else the caller ([caller_jid], or the chat for bridges
     * that predate it).
     */
    fun callerTitle(evt: BridgeEvent, stored: Conversation?): String {
        val conversationJid = evt.string("conversation_jid").orEmpty()
        val isGroup = evt.boolean("is_group") ?: stored?.isGroup ?: conversationJid.endsWith("@g.us")
        val callerName = evt.string("caller_name")?.takeIf { it.isNotBlank() }
        if (isGroup) return DisplayNames.group(Platform.WHATSAPP, callerName ?: stored?.name, conversationJid)
        val callerJid = evt.string("caller_jid")?.takeIf { it.isNotBlank() } ?: conversationJid
        return person(callerJid, callerName ?: stored?.name)
    }
}
