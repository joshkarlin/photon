package app.photon.signal

/**
 * Read syncs that arrived before the message they mark read, keyed by the
 * message's "{authorAci}_{timestampMs}" prefix. This happens when the
 * original envelope failed to decrypt and the sender resends after our retry
 * receipt, or when the server redelivers it late: the primary has already
 * read it, so when it does land it must not count as unread or notify.
 *
 * In memory only, bounded (oldest entries are dropped first) and
 * time-limited, so a read for a message that never arrives can't linger.
 */
internal class EarlyReadCache(
    private val maxEntries: Int = 1_000,
    private val ttlMs: Long = 24L * 60 * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Insertion-ordered: the first entry is always the oldest.
    private val readAt = LinkedHashMap<String, Long>()

    @Synchronized
    fun remember(messagePrefix: String) {
        val now = clock()
        evictExpired(now)
        readAt.remove(messagePrefix)
        readAt[messagePrefix] = now
        while (readAt.size > maxEntries) {
            readAt.remove(readAt.keys.first())
        }
    }

    /** True (once) if a still-fresh read sync was recorded for this message. */
    @Synchronized
    fun consume(messagePrefix: String): Boolean {
        val at = readAt.remove(messagePrefix) ?: return false
        return clock() - at <= ttlMs
    }

    @Synchronized
    fun clear() = readAt.clear()

    private fun evictExpired(now: Long) {
        val it = readAt.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > ttlMs) it.remove() else break
        }
    }
}
