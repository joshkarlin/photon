package app.photon.ui.whatsapp

import app.photon.data.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarkReadRequestTest {

    private fun msg(id: String, fromMe: Boolean = false, type: String = "text") = Message(
        id = id, conversationJid = "c", senderJid = "s", timestamp = 0, contentType = type,
        textBody = null, mediaUrl = null, mediaMime = null, mediaSize = null, thumbnailPath = null,
        stickerPackId = null, replyToId = null, editVersion = 0, isFromMe = fromMe, status = "sent",
    )

    @Test
    fun receiptCoversIncomingMessagesOnly() {
        // Newest first: a missed call, our reply, then two incoming messages.
        val messages = listOf(msg("call_c1", type = "call"), msg("mine", fromMe = true), msg("m2"), msg("m1"))
        assertEquals("m2" to listOf("m2", "m1"), markReadRequest(messages, lastMarkedReadId = null))
    }

    @Test
    fun nothingToSendOnceTheNewestIsMarked() {
        assertNull(markReadRequest(listOf(msg("m2"), msg("m1")), lastMarkedReadId = "m2"))
    }

    @Test
    fun aNewerMessageNeedsAnotherReceipt() {
        assertEquals("m3", markReadRequest(listOf(msg("m3"), msg("m2")), lastMarkedReadId = "m2")?.first)
    }

    @Test
    fun capsAtTwentyIds() {
        val messages = List(30) { msg("m$it") }
        assertEquals(20, markReadRequest(messages, null)?.second?.size)
    }

    @Test
    fun onlyCallsOrOwnMessagesSendNothing() {
        assertNull(markReadRequest(listOf(msg("call_c1", type = "call"), msg("mine", fromMe = true)), null))
    }
}
