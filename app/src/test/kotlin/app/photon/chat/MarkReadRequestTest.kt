package app.photon.chat

import app.photon.data.model.Message
import app.photon.data.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarkReadRequestTest {

    private fun msg(id: String, fromMe: Boolean = false, type: String = "text") = Message(
        id = id, conversationJid = "c", senderJid = "s", timestamp = 0, contentType = type,
        textBody = null, mediaUrl = null, mediaMime = null, mediaSize = null, thumbnailPath = null,
        stickerPackId = null, replyToId = null, editVersion = 0, isFromMe = fromMe, status = MessageStatus.UNREAD,
    )

    @Test
    fun coversIncomingRowsOnly() {
        // Newest first: our reply, then two incoming messages.
        val messages = listOf(msg("mine", fromMe = true), msg("m2"), msg("m1"))
        assertEquals("m2" to listOf("m2", "m1"), markReadRequest(messages, lastMarkedReadId = null))
    }

    @Test
    fun aMissedCallOnTopIsTheNewestRow() {
        // The bridge reads through the call and leaves it out of the receipt.
        val messages = listOf(msg("call_c1", type = "call"), msg("mine", fromMe = true), msg("m1"))
        assertEquals("call_c1" to listOf("call_c1", "m1"), markReadRequest(messages, lastMarkedReadId = null))
    }

    @Test
    fun aChatOfOnlyCallsIsStillMarkedRead() {
        val messages = listOf(msg("call_c2", type = "call"), msg("call_c1", type = "call"))
        assertEquals("call_c2", markReadRequest(messages, lastMarkedReadId = null)?.first)
    }

    @Test
    fun nothingToSendOnceTheNewestIsMarked() {
        assertNull(markReadRequest(listOf(msg("m2"), msg("m1")), lastMarkedReadId = "m2"))
        assertNull(markReadRequest(listOf(msg("call_c1", type = "call")), lastMarkedReadId = "call_c1"))
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
    fun onlyOwnMessagesSendNothing() {
        assertNull(markReadRequest(listOf(msg("mine", fromMe = true)), null))
    }
}
