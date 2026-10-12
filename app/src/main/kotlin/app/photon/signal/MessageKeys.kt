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

    /**
     * The id of row [index] of a message stored as several rows (one per
     * attachment; see plannedRows): the first is [messageId] itself, the
     * rest add ".2", ".3", … after the random suffix, so every row keeps the
     * message's prefix, and sorts after the first.
     */
    fun partId(messageId: String, index: Int): String =
        if (index == 0) messageId else "$messageId.${index + 1}"

    /** Whether [messageId] is a later row of a message stored as several ([partId]). */
    fun isExtraPart(messageId: String): Boolean = messageId.substringAfterLast("_").contains('.')

    /** Which row of its message [messageId] is: 1 for the first, 2 for ".2", and so on. */
    fun partNumber(messageId: String): Int =
        if (isExtraPart(messageId)) messageId.substringAfterLast('.').toIntOrNull() ?: 1 else 1

    /** The id of the first row of [messageId]'s message (which may itself be gone). */
    fun firstPartId(messageId: String): String =
        if (isExtraPart(messageId)) messageId.substringBeforeLast('.') else messageId

    /** `(authorAci, timestampMs)` parsed from a message id, or null if malformed. */
    fun parse(messageId: String): Pair<String, Long>? {
        val prefix = prefixOf(messageId)
        val timestampMs = prefix.substringAfterLast("_").toLongOrNull() ?: return null
        val author = prefix.substringBeforeLast("_")
        if (author.isEmpty()) return null
        return author to timestampMs
    }

    /**
     * The wire (sent) timestamp in ms of a stored message. The id carries the
     * exact value; the `timestamp` column is truncated to seconds, so
     * rebuilding from it (`seconds * 1000`) yields a different timestamp that
     * recipients can't dedupe against and receipts/reactions won't match.
     * [fallbackSeconds] is only used for ids that don't parse.
     */
    fun sentTimestampMs(messageId: String, fallbackSeconds: Long): Long =
        parse(messageId)?.second ?: (fallbackSeconds * 1000)

    /**
     * Remap values keyed by message-id *prefix* back onto the full message ids
     * supplied. Used to attach reactions (stored by prefix) to the full ids the
     * UI indexes by. A message stored as several rows shows them once, on its
     * first row. Preserves input order.
     */
    fun <T> remapByPrefix(messageIds: List<String>, byPrefix: Map<String, T>): Map<String, T> {
        val out = LinkedHashMap<String, T>()
        for (id in messageIds) if (!isExtraPart(id)) byPrefix[prefixOf(id)]?.let { out[id] = it }
        return out
    }
}
