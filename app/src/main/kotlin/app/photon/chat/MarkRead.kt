package app.photon.chat

import app.photon.data.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The mark-read request for a chat's messages (newest first): the newest
 * incoming row's id plus up to 20 incoming ids, or null when that one is
 * already marked. Call rows count: a missed call is unread like a message, so
 * a chat whose newest row is a call must still be marked read. The bridge
 * leaves them out of the receipt it sends WhatsApp (their ids are Photon's
 * own, or call logs WhatsApp takes no receipt for).
 */
internal fun markReadRequest(messages: List<Message>, lastMarkedReadId: String?): Pair<String, List<String>>? {
    val incoming = messages.filter { !it.isFromMe }
    val newestId = incoming.firstOrNull()?.id ?: return null
    if (newestId == lastMarkedReadId) return null
    return newestId to incoming.take(20).map { it.id }
}

/**
 * Marks a chat read as its messages arrive: only when the newest incoming
 * message is one it hasn't marked, recording it only once the request
 * succeeds. A failed request is asked again on the next change or [retry]
 * (the platform reconnected); one at a time, and one that succeeds while
 * newer messages arrived is followed by another for them.
 *
 * Main thread only, like the screen that owns it.
 */
internal class MarkReadGate(
    private val scope: CoroutineScope,
    private val markRead: suspend (incomingIds: List<String>) -> Unit,
) {
    private var latest: List<Message> = emptyList()
    private var lastMarkedId: String? = null
    private var inFlight = false

    /** The chat's messages (newest first) changed. */
    fun onMessages(messages: List<Message>) {
        latest = messages
        fire()
    }

    fun retry() = fire()

    private fun fire() {
        if (inFlight) return
        val (newestId, ids) = markReadRequest(latest, lastMarkedId) ?: return
        inFlight = true
        scope.launch {
            try {
                markRead(ids)
                lastMarkedId = newestId
            } catch (_: ChatBackendException) {
                // Not recorded, so the next change or retry asks again.
            } finally {
                inFlight = false
            }
            // Catch up on messages that arrived while the request was out.
            if (lastMarkedId == newestId) fire()
        }
    }
}
