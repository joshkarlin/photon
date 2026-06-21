package app.photon.signal

/**
 * Signal local message ids are `"{authorAci}_{timestampMs}_{rand}"`. Reactions,
 * read receipts, and remote-deletes all key on the suffix-less
 * `"{authorAci}_{timestampMs}"` prefix (author + sent timestamp uniquely
 * identify a message across devices; the random suffix only gives the local row
 * a unique primary key).
 *
 * Centralised here so every path derives the same keys — the reaction-display
 * and remote-delete bugs both came from one path using the full id and another
 * using the prefix.
 */
object MessageKeys {

    /** The `"{author}_{timestampMs}"` prefix a message shares with its reactions. */
    fun prefixOf(messageId: String): String = messageId.substringBeforeLast("_")

    /** `(authorAci, timestampMs)` parsed from a message id, or null if malformed. */
    fun parse(messageId: String): Pair<String, Long>? {
        val prefix = prefixOf(messageId)
        val timestampMs = prefix.substringAfterLast("_").toLongOrNull() ?: return null
        val author = prefix.substringBeforeLast("_")
        if (author.isEmpty()) return null
        return author to timestampMs
    }

    /**
     * Remap values keyed by message-id *prefix* back onto the full message ids
     * supplied. Used to attach reactions (stored by prefix) to the full ids the
     * UI indexes by. Preserves input order.
     */
    fun <T> remapByPrefix(messageIds: List<String>, byPrefix: Map<String, T>): Map<String, T> {
        val out = LinkedHashMap<String, T>()
        for (id in messageIds) byPrefix[prefixOf(id)]?.let { out[id] = it }
        return out
    }
}
