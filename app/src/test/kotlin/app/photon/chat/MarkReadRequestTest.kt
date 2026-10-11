package app.photon.chat

import app.photon.data.model.Message
import app.photon.data.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarkReadRequestTest {

    private fun msg(
        id: String,
        fromMe: Boolean = false,
        type: String = "text",
        status: MessageStatus = if (fromMe) MessageStatus.SENT else MessageStatus.UNREAD,
    ) = Message(
        id = id, conversationJid = "c", senderJid = "s", timestamp = 0, contentType = type,
        textBody = null, mediaUrl = null, mediaMime = null, mediaSize = null, thumbnailPath = null,
        stickerPackId = null, replyToId = null, editVersion = 0, isFromMe = fromMe, status = status,
    )

    private fun read(id: String) = msg(id, status = MessageStatus.READ)

    @Test
    fun coversIncomingRowsOnly() {
        // Newest first: our reply, then two incoming messages.
        val messages = listOf(msg("mine", fromMe = true), msg("m2"), msg("m1"))
        assertEquals(
            MarkReadRequest(setOf("m2", "m1"), listOf("m2", "m1")),
            markReadRequest(messages, marked = emptySet()),
        )
    }

    @Test
    fun aMissedCallOnTopIsTheNewestRow() {
        // The bridge reads through the call and leaves it out of the receipt.
        val messages = listOf(msg("call_c1", type = "call"), msg("mine", fromMe = true), read("m1"))
        assertEquals(
            MarkReadRequest(setOf("call_c1"), listOf("call_c1", "m1")),
            markReadRequest(messages, marked = emptySet()),
        )
    }

    @Test
    fun aChatOfOnlyCallsIsStillMarkedRead() {
        val messages = listOf(msg("call_c2", type = "call"), msg("call_c1", type = "call"))
        assertEquals(listOf("call_c2", "call_c1"), markReadRequest(messages, marked = emptySet())?.ids)
    }

    @Test
    fun nothingToSendOnceEverythingItCoversIsMarked() {
        assertNull(markReadRequest(listOf(msg("m2"), msg("m1")), marked = setOf("m2", "m1")))
        // Read since: only the newest is still covered.
        assertNull(markReadRequest(listOf(read("m2"), read("m1")), marked = setOf("m2", "m1")))
        assertNull(markReadRequest(listOf(msg("call_c1", type = "call")), marked = setOf("call_c1")))
    }

    @Test
    fun aNewerMessageNeedsAnotherReceipt() {
        assertEquals(setOf("m3"), markReadRequest(listOf(msg("m3"), read("m2")), marked = setOf("m2"))?.covers)
    }

    @Test
    fun anUnreadMessageBelowTheNewestNeedsOneToo() {
        // Sent while its sender was offline: it sorts under messages already read.
        val messages = listOf(read("m3"), read("m2"), msg("late"), read("m1"))
        val request = markReadRequest(messages, marked = setOf("m3", "m2"))
        assertEquals(setOf("m3", "late"), request?.covers)
        assertEquals(listOf("m3", "m2", "late", "m1"), request?.ids)
    }

    @Test
    fun capsAtTwentyIds() {
        val messages = List(30) { msg("m$it") }
        assertEquals(20, markReadRequest(messages, marked = emptySet())?.ids?.size)
    }

    @Test
    fun onlyOwnMessagesSendNothing() {
        assertNull(markReadRequest(listOf(msg("mine", fromMe = true)), marked = emptySet()))
    }
}
