package app.photon.sms

import app.photon.data.model.Message
import app.photon.data.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingSendsTest {

    private val sends = PendingSends()
    private val chat = "+447700900123"

    private fun stored(id: String, text: String, atSeconds: Long, fromMe: Boolean = true) = Message(
        id = id, conversationJid = chat, senderJid = if (fromMe) "me" else chat, timestamp = atSeconds,
        contentType = "text", textBody = text, mediaUrl = null, mediaMime = null, mediaSize = null,
        thumbnailPath = null, stickerPackId = null, replyToId = null, editVersion = 0, isFromMe = fromMe,
        status = if (fromMe) MessageStatus.SENT else MessageStatus.READ,
    )

    @Test
    fun aMultipartSendSettlesOnItsLastPartAndFailsIfAnyPartDid() {
        val id = sends.add(chat, chat, "long", nowMillis = 10_000)
        sends.submitted(id, parts = 3)
        assertFalse(sends.partSent(id, ok = true))
        assertFalse(sends.partSent(id, ok = false))
        assertTrue(sends.partSent(id, ok = true))
        assertEquals(MessageStatus.FAILED, sends.messages(chat, emptyList()).single().status)
        assertEquals(chat to "long", sends.retryable(id))
        // A late result for a settled send changes nothing.
        assertFalse(sends.partSent(id, ok = true))
    }

    @Test
    fun onlyAFailedSendIsRetryable() {
        val id = sends.add(chat, chat, "hi", nowMillis = 10_000)
        sends.submitted(id, parts = 1)
        assertNull(sends.retryable(id))
        sends.partSent(id, ok = true)
        assertNull(sends.retryable(id))
    }

    @Test
    fun eachStoredRowAccountsForOneSendMadeBeforeIt() {
        val first = sends.add(chat, chat, "ok", nowMillis = 10_000)
        val second = sends.add(chat, chat, "ok", nowMillis = 11_000)
        val stored = listOf(
            // An "ok" from before either send isn't one of them.
            stored("1", "ok", atSeconds = 5),
            stored("2", "ok", atSeconds = 10),
            stored("3", "ok", atSeconds = 9, fromMe = false),
        )

        val shown = sends.messages(chat, stored)

        assertEquals(listOf(second), shown.map { it.id })
        assertEquals(listOf(second), sends.messages(chat, emptyList()).map { it.id }, "the stored one is forgotten")
        assertNull(sends.retryable(first))
    }

    @Test
    fun sendsShowOnlyInTheirOwnChat() {
        sends.add(chat, chat, "hi", nowMillis = 10_000)
        assertEquals(emptyList(), sends.messages("+447700900999", emptyList()))
    }
}
