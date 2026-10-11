package app.photon.chat

import app.photon.data.model.Message
import app.photon.data.model.MessageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A mark-read to send: what it [covers], and the incoming [ids] it names. */
internal data class MarkReadRequest(val covers: Set<String>, val ids: List<String>)

/**
 * The mark-read request for a chat's messages (newest first), or null when
 * [marked] already covers it. A request covers the newest incoming message
 * and every incoming one still unread, so a message that arrives below the
 * newest (sent while its sender was offline, or with their clock behind) is
 * marked too. It names the newest incoming row plus up to 19 more.
 *
 * Call rows count: a missed call is unread like a message, so a chat whose
 * newest row is a call must still be marked read. WhatsApp's bridge leaves
 * them out of the receipt it sends (their ids are Photon's own, or call logs
 * WhatsApp takes no receipt for).
 */
internal fun markReadRequest(messages: List<Message>, marked: Set<String>): MarkReadRequest? {
    val incoming = messages.filter { !it.isFromMe }
    val newest = incoming.firstOrNull() ?: return null
    val covers = buildSet {
        add(newest.id)
        incoming.filter { it.status == MessageStatus.UNREAD }.forEach { add(it.id) }
    }
    if (marked.containsAll(covers)) return null
    return MarkReadRequest(covers, incoming.take(20).map { it.id })
}

/**
 * Marks a chat read as its messages arrive: only when they hold an incoming
 * message it hasn't marked (see [markReadRequest]), recording it only once
 * the request succeeds. A failed request is asked again on the next change
 * or [retry] (the platform reconnected). One request at a time; when it
 * ends, messages that arrived meanwhile get one of their own.
 *
 * Main thread only, like the screen that owns it.
 */
internal class MarkReadGate(
    private val scope: CoroutineScope,
    private val markRead: suspend (incomingIds: List<String>) -> Unit,
) {
    private var latest: List<Message> = emptyList()
    private var marked: Set<String> = emptySet()
    private var inFlight = false

    /** The chat's messages (newest first) changed. */
    fun onMessages(messages: List<Message>) {
        latest = messages
        fire()
    }

    fun retry() = fire()

    private fun fire() {
        if (inFlight) return
        val request = markReadRequest(latest, marked) ?: return
        inFlight = true
        scope.launch {
            val succeeded = try {
                markRead(request.ids)
                marked = request.covers
                true
            } catch (_: ChatBackendException) {
                // Not recorded, so the next change or retry asks again.
                false
            } finally {
                inFlight = false
            }
            // Catch up on messages that arrived while the request was out. A
            // failure isn't simply repeated: only something new asks again.
            val next = markReadRequest(latest, marked)
            if (succeeded || (next != null && !request.covers.containsAll(next.covers))) fire()
        }
    }
}
