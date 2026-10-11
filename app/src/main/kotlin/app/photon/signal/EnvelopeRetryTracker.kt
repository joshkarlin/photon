package app.photon.signal

import org.whispersystems.signalservice.internal.push.Envelope

/**
 * Envelopes whose local processing failed and were left un-acked, to be
 * processed again by an in-process retry or the server's redelivery after a
 * reconnect. Two jobs:
 *
 *  - Poison protection: after [maxFailures] failures for the same envelope
 *    the caller gives up and acks it, so one bad envelope can't be
 *    redelivered forever.
 *  - Keeping the decrypted payload: decryption advances the Signal ratchet,
 *    so a redelivered copy can't be decrypted again (it reports a duplicate).
 *    The payload is held here and reprocessed instead. In memory only: after
 *    a process restart the redelivery decrypts as a duplicate and is lost,
 *    as before.
 *
 * Bounded (least recently touched entries are dropped first).
 */
internal class EnvelopeRetryTracker<T : Any>(
    private val maxFailures: Int = 3,
    private val maxEntries: Int = 256,
) {
    private class Entry<T>(var failures: Int, var payload: T?)

    private val entries = object : LinkedHashMap<String, Entry<T>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<T>>?): Boolean =
            size > maxEntries
    }

    /** The payload held from an earlier failed attempt, if any. */
    @Synchronized
    fun pending(key: String): T? = entries[key]?.payload

    /**
     * Record a failed attempt, holding [payload] for the redelivery. Returns
     * true when the envelope has now failed [maxFailures] times: the caller
     * should log once and ack it. The entry is dropped in that case.
     */
    @Synchronized
    fun recordFailure(key: String, payload: T?): Boolean {
        val entry = entries.getOrPut(key) { Entry(0, null) }
        entry.failures++
        if (payload != null) entry.payload = payload
        if (entry.failures >= maxFailures) {
            entries.remove(key)
            return true
        }
        return false
    }

    /** Whether the envelope is still waiting to be processed. */
    @Synchronized
    fun isHeld(key: String): Boolean = entries.containsKey(key)

    @Synchronized
    fun clear(key: String) {
        entries.remove(key)
    }

    @Synchronized
    fun clearAll() = entries.clear()
}

/**
 * Stable identity of a server envelope across redeliveries: the server GUID,
 * or sender/device/timestamps when an old server omits it.
 */
internal fun envelopeRetryKey(envelope: Envelope): String =
    envelope.serverGuid?.takeIf { it.isNotBlank() }
        ?: envelope.serverGuidBinary?.takeIf { it.size > 0 }?.hex()
        ?: listOf(
            envelope.sourceServiceId,
            envelope.sourceDeviceId,
            envelope.clientTimestamp,
            envelope.serverTimestamp,
        ).joinToString(":")
