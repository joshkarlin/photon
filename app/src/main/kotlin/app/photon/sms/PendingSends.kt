package app.photon.sms

import app.photon.data.model.Message
import app.photon.data.model.MessageStatus
import java.util.UUID

/**
 * SMS sent from Photon, from the moment they're sent until the SMS provider
 * has them. Android writes a sent message to the provider for an app that
 * isn't the default SMS app only once the radio has reported it sent or
 * failed. Until then these show it as sending; if SmsManager refused it
 * outright, or the provider never gets it, they show how it ended, so a
 * failed send can still be retried.
 */
internal class PendingSends {

    private class Send(
        val id: String,
        val address: String,
        val conversationJid: String,
        val text: String,
        val sentAtMillis: Long,
    ) {
        var status = MessageStatus.SENDING
        var partsLeft = 0
        var anyPartFailed = false
    }

    // Oldest first. Guarded by this.
    private val sends = LinkedHashMap<String, Send>()

    /** A new send of [text] to [address] in [conversationJid]; returns its id. */
    @Synchronized
    fun add(address: String, conversationJid: String, text: String, nowMillis: Long): String {
        val id = "$ID_PREFIX${UUID.randomUUID()}"
        sends[id] = Send(id, address, conversationJid, text, nowMillis)
        return id
    }

    /** Where to send [id] again, if it is one of these and failed. */
    @Synchronized
    fun retryable(id: String): Pair<String, String>? =
        sends[id]?.takeIf { it.status == MessageStatus.FAILED }?.let { it.address to it.text }

    /** [id] went to the radio in [parts] parts; each reports back through [partSent]. */
    @Synchronized
    fun submitted(id: String, parts: Int) {
        val send = sends[id] ?: return
        send.status = MessageStatus.SENDING
        send.partsLeft = parts
        send.anyPartFailed = false
    }

    /** [id] couldn't be sent at all. */
    @Synchronized
    fun failed(id: String) {
        sends[id]?.status = MessageStatus.FAILED
    }

    /** The radio's result for one part of [id]. Returns whether that settled the send. */
    @Synchronized
    fun partSent(id: String, ok: Boolean): Boolean {
        val send = sends[id] ?: return false
        if (send.status != MessageStatus.SENDING || send.partsLeft == 0) return false
        if (!ok) send.anyPartFailed = true
        send.partsLeft--
        if (send.partsLeft > 0) return false
        send.status = if (send.anyPartFailed) MessageStatus.FAILED else MessageStatus.SENT
        return true
    }

    /**
     * The sends in [conversationJid] as messages, leaving out (and
     * forgetting) each one the provider now has: an outgoing row in [stored]
     * with the same text, written no earlier than the send. A stored row
     * accounts for one send only.
     */
    @Synchronized
    fun messages(conversationJid: String, stored: List<Message>): List<Message> {
        val unmatched = stored.filter { it.isFromMe }.sortedBy { it.timestamp }.toMutableList()
        val shown = mutableListOf<Message>()
        val iterator = sends.values.iterator()
        while (iterator.hasNext()) {
            val send = iterator.next()
            if (send.conversationJid != conversationJid) continue
            val row = unmatched.firstOrNull { it.textBody == send.text && it.timestamp >= send.sentAtMillis / 1000 }
            if (row != null) {
                unmatched.remove(row)
                iterator.remove()
            } else {
                shown += send.toMessage()
            }
        }
        return shown
    }

    private fun Send.toMessage() = Message(
        id = id,
        conversationJid = conversationJid,
        senderJid = "me",
        timestamp = sentAtMillis / 1000,
        contentType = "text",
        textBody = text,
        mediaUrl = null,
        mediaMime = null,
        mediaSize = null,
        thumbnailPath = null,
        stickerPackId = null,
        replyToId = null,
        editVersion = 0,
        isFromMe = true,
        status = status,
    )

    companion object {
        /** Their ids, unlike the provider's numeric ones. */
        const val ID_PREFIX = "pending:"
    }
}
