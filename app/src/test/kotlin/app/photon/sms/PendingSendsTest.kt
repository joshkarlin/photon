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

    private fun shown(stored: List<Message> = emptyList()) = sends.messages(chat, stored)

    @Test
    fun aSendIsForgottenOnceEveryPartHasReported() {
        val id = sends.add(chat, chat, "long", nowMillis = 10_000, newestRowId = 0)
        sends.submitted(id, parts = 3)
        assertFalse(sends.partSent(id))
        assertFalse(sends.partSent(id))
        assertEquals(MessageStatus.SENDING, shown().single().status)
        assertTrue(sends.partSent(id))
        assertEquals(emptyList(), shown(), "Android has stored it by now")
        // A late report for a forgotten send changes nothing.
        assertFalse(sends.partSent(id))
    }

    @Test
    fun aRefusedSendShowsFailedAndIsRetriedOnceAsItself() {
        val id = sends.add(chat, chat, "hi", nowMillis = 10_000, newestRowId = 0)
        sends.failed(id)
        assertEquals(MessageStatus.FAILED, shown().single().status)

        assertEquals(chat to "hi", sends.retry(id))
        assertNull(sends.retry(id), "a second tap doesn't send it twice")
        assertEquals(MessageStatus.SENDING, shown().single().status)
    }

    @Test
    fun onlyAFailedSendCanBeRetried() {
        val id = sends.add(chat, chat, "hi", nowMillis = 10_000, newestRowId = 0)
        sends.submitted(id, parts = 1)
        assertNull(sends.retry(id))
    }

    @Test
    fun aSendingRowTheProviderAlreadyHasIsLeftOutOneRowPerSend() {
        // The chat showed row 1 when both were sent, in the same second.
        val first = sends.add(chat, chat, "ok", nowMillis = 10_000, newestRowId = 1)
        val second = sends.add(chat, chat, "ok", nowMillis = 10_000, newestRowId = 1)
        listOf(first, second).forEach { sends.submitted(it, parts = 1) }
        val stored = listOf(
            // The "ok" already there isn't one of them.
            stored("1", "ok", atSeconds = 10),
            stored("2", "ok", atSeconds = 10),
            stored("3", "ok", atSeconds = 10, fromMe = false),
        )
        assertEquals(listOf(second), shown(stored).map { it.id })
    }

    @Test
    fun aFailedSendAlwaysShows() {
        val id = sends.add(chat, chat, "ok", nowMillis = 10_000, newestRowId = 0)
        sends.failed(id)
        assertEquals(listOf(id), shown(listOf(stored("1", "ok", atSeconds = 10))).map { it.id })
    }

    @Test
    fun sendsShowOnlyInTheirOwnChat() {
        sends.add(chat, chat, "hi", nowMillis = 10_000, newestRowId = 0)
        assertEquals(emptyList(), sends.messages("+447700900999", emptyList()))
    }
}
