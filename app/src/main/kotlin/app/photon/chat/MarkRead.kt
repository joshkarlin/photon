package app.photon.chat

import app.photon.data.model.Message

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
