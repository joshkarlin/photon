package app.photon.chat

import app.photon.data.model.Message
import app.photon.data.model.MessageStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/** The one mark-read policy every platform's chat follows. */
class MarkReadGateTest {

    private fun msg(id: String, fromMe: Boolean = false) = Message(
        id = id, conversationJid = "c", senderJid = "s", timestamp = 0, contentType = "text",
        textBody = null, mediaUrl = null, mediaMime = null, mediaSize = null, thumbnailPath = null,
        stickerPackId = null, replyToId = null, editVersion = 0, isFromMe = fromMe,
        status = if (fromMe) MessageStatus.SENT else MessageStatus.UNREAD,
    )

    /** Each request, and how it ends: completing [result] with false fails it. */
    private class Requests {
        val asked = mutableListOf<List<String>>()
        var result = CompletableDeferred<Boolean>()

        suspend fun markRead(ids: List<String>) {
            asked += ids
            if (!result.await()) throw ChatBackendException("offline")
        }
    }

    private val requests = Requests()

    // Unconfined: each request runs to its first suspension at once, as on the main thread.
    private val gate = MarkReadGate(CoroutineScope(Dispatchers.Unconfined), requests::markRead)

    @Test
    fun marksOncePerNewestIncomingMessage() {
        requests.result.complete(true)
        val messages = listOf(msg("m2"), msg("m1"))
        gate.onMessages(messages)
        gate.onMessages(messages)
        // Our own reply arriving isn't a new incoming message.
        gate.onMessages(listOf(msg("mine", fromMe = true)) + messages)
        assertEquals(listOf(listOf("m2", "m1")), requests.asked)

        gate.onMessages(listOf(msg("m3")) + messages)
        assertEquals(listOf("m3", "m2", "m1"), requests.asked.last())
    }

    @Test
    fun aFailedRequestIsAskedAgainOnTheNextChangeOrRetry() {
        requests.result.complete(false)
        val messages = listOf(msg("m1"))
        gate.onMessages(messages)
        gate.onMessages(messages)
        assertEquals(2, requests.asked.size, "a failure isn't recorded")

        requests.result = CompletableDeferred(true)
        gate.retry()
        assertEquals(3, requests.asked.size)
        gate.retry()
        gate.onMessages(messages)
        assertEquals(3, requests.asked.size, "marked once it succeeded")
    }

    @Test
    fun oneRequestAtATimeThenCatchesUp() {
        gate.onMessages(listOf(msg("m1")))
        gate.onMessages(listOf(msg("m2"), msg("m1")))
        assertEquals(1, requests.asked.size, "nothing more while one is out")

        requests.result.complete(true)
        assertEquals(listOf(listOf("m1"), listOf("m2", "m1")), requests.asked)
    }

    @Test
    fun aChatWithNothingIncomingIsNeverMarked() {
        requests.result.complete(true)
        gate.onMessages(emptyList())
        gate.onMessages(listOf(msg("mine", fromMe = true)))
        gate.retry()
        assertEquals(emptyList(), requests.asked)
    }
}
