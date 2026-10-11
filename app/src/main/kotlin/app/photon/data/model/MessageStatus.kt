package app.photon.data.model

/**
 * Where a message stands, in one vocabulary for every platform. Each
 * platform keeps its own strings in its store, and its repository maps them
 * to this as it reads a row, so nothing stored changes.
 *
 * Your own messages go [SENDING] → [SENT] → [DELIVERED] → [READ], or end
 * [FAILED] for you to retry. Incoming messages are [UNREAD] until read in
 * Photon or on another device ([READ]); Signal has a step between, [SEEN]:
 * read here but not yet synced to your other devices. A message deleted for
 * everyone is [DELETED], whoever sent it.
 */
enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED,
    UNREAD,
    SEEN,
    DELETED,
    ;

    /** One of your messages that hasn't reached the server, so nothing can refer to it yet. */
    val isUnsent: Boolean get() = this == SENDING || this == FAILED

    companion object {
        /** The strings WhatsApp's bridge and Signal both store for your own messages. */
        fun outgoing(stored: String): MessageStatus = when (stored) {
            "sending" -> SENDING
            "delivered" -> DELIVERED
            "read" -> READ
            "failed" -> FAILED
            "deleted" -> DELETED
            else -> SENT
        }
    }
}
