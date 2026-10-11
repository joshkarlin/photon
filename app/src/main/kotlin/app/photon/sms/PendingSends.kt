package app.photon.sms

import app.photon.data.model.Message
import app.photon.data.model.MessageStatus
import java.util.UUID

/**
 * SMS sent from Photon, from the moment they're sent until the SMS provider
 * has them. For an app that isn't the default SMS app, Android writes a sent
 * message to the provider (as sent or failed) only once the radio has
 * reported every part, just before the last report reaches Photon. Until
 * then these show it as sending. One SmsManager refused outright never
 * reaches the provider: it shows as failed here, to be retried as itself.
 */
internal class PendingSends {

    private class Send(
        val id: String,
        val address: String,
        val conversationJid: String,
        val text: String,
        val sentAtMillis: Long,
        val newerThanRow: Long,
    ) {
        var status = MessageStatus.SENDING
        var partsLeft = 0
    }

    // Oldest first. Guarded by this.
    private val sends = LinkedHashMap<String, Send>()

    /**
     * A new send of [text] to [address] in [conversationJid]; returns its id.
     * [newestRowId] is the newest provider row the chat has shown: the
     * provider's ids only grow, so the send's own row will be newer.
     */
    @Synchronized
    fun add(address: String, conversationJid: String, text: String, nowMillis: Long, newestRowId: Long): String {
        val id = "$ID_PREFIX${UUID.randomUUID()}"
        sends[id] = Send(id, address, conversationJid, text, nowMillis, newestRowId)
        return id
    }

    /**
     * Take [id] to send again, if it is one of these and failed: it shows as
     * sending from now. Returns where to send what, or null.
     */
    @Synchronized
    fun retry(id: String): Pair<String, String>? {
        val send = sends[id]?.takeIf { it.status == MessageStatus.FAILED } ?: return null
        send.status = MessageStatus.SENDING
        return send.address to send.text
    }

    /** [id] went to the radio in [parts] parts; each reports back through [partSent]. */
    @Synchronized
    fun submitted(id: String, parts: Int) {
        sends[id]?.partsLeft = parts
    }

    /** [id] couldn't be sent at all. */
    @Synchronized
    fun failed(id: String) {
        sends[id]?.status = MessageStatus.FAILED
    }

    /**
     * The radio's result for one part of [id], sent or failed alike: once
     * every part has reported, Android has stored the message, so it's
     * forgotten here. Returns whether that happened.
     */
    @Synchronized
    fun partSent(id: String): Boolean {
        val send = sends[id] ?: return false
        if (send.status != MessageStatus.SENDING || send.partsLeft == 0) return false
        send.partsLeft--
        if (send.partsLeft > 0) return false
        sends.remove(id)
        return true
    }

    /**
     * The sends in [conversationJid] as messages. A send still going out is
     * left out when [stored] already has it (Android stores it just before
     * reporting the last part): an outgoing row with the same text, newer
     * than any the chat showed before the send, each row standing for one
     * send.
     */
    @Synchronized
    fun messages(conversationJid: String, stored: List<Message>): List<Message> {
        val unmatched = stored.filter { it.isFromMe }.sortedBy { rowId(it) }.toMutableList()
        return sends.values.filter { it.conversationJid == conversationJid }.mapNotNull { send ->
            val row = if (send.status != MessageStatus.SENDING) null else unmatched.firstOrNull {
                it.textBody == send.text && rowId(it) > send.newerThanRow
            }
            if (row != null) {
                unmatched.remove(row)
                null
            } else {
                send.toMessage()
            }
        }
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

        /** A provider row's id, as a number (0 for a send's own). */
        fun rowId(message: Message): Long = message.id.toLongOrNull() ?: 0
    }
}
