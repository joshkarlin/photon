package app.photon.signal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reconnect policy for the Signal receiver. The old loop retried every 5s
 * forever — including after the server rejected the credentials — and
 * re-ran profile, name, group and pre-key upkeep on every attempt.
 */
internal object SignalReconnect {
    const val BASE_DELAY_MS = 5_000L
    const val MAX_DELAY_MS = 5 * 60_000L

    // A connection that stayed up this long was healthy: its drop starts a
    // fresh backoff instead of continuing the previous one.
    const val STABLE_CONNECTION_MS = 60_000L

    // Consecutive 401/403 handshakes before treating the device as unlinked.
    // One retry absorbs a transient server hiccup.
    const val AUTH_FAILURES_BEFORE_LOGOUT = 2

    // Post-connect upkeep (profile sweep, contact-name refresh, group
    // metadata, pre-key top-up) runs at most this often across reconnects.
    const val MAINTENANCE_INTERVAL_MS = 10 * 60_000L

    /** Delay before the next attempt after [consecutiveFailures] (≥ 1) failed connections. */
    fun delayMs(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 1) return BASE_DELAY_MS
        val doublings = (consecutiveFailures - 1).coerceAtMost(16)
        return (BASE_DELAY_MS shl doublings).coerceAtMost(MAX_DELAY_MS)
    }

    /**
     * Failure count after a connection ended. [connectedForMs] is how long it
     * was up, or null if it never connected.
     */
    fun nextFailureCount(previous: Int, connectedForMs: Long?): Int =
        if (connectedForMs != null && connectedForMs >= STABLE_CONNECTION_MS) 1 else previous + 1

    fun maintenanceDue(lastRunMs: Long, nowMs: Long): Boolean =
        lastRunMs <= 0 || nowMs - lastRunMs >= MAINTENANCE_INTERVAL_MS
}

/**
 * Cuts a reconnect backoff short when a validated network appears, so the
 * receiver doesn't sit out up to [SignalReconnect.MAX_DELAY_MS] after an
 * outage (offers arriving in that window would only be seen as missed).
 * The receive loop notes [epoch] when an attempt starts; a network validated
 * after that point ends the wait, and the backoff starts over.
 */
internal class NetworkReturn {
    private val validations = MutableStateFlow(0L)

    val epoch: Long get() = validations.value

    fun networkValidated() = validations.update { it + 1 }

    /**
     * Wait up to [delayMs] before the next attempt. Returns true, at once or
     * as soon as it happens, if a network was validated after [sinceEpoch].
     */
    suspend fun await(delayMs: Long, sinceEpoch: Long): Boolean =
        withTimeoutOrNull(delayMs) { validations.first { it > sinceEpoch } } != null

    companion object {
        /** Failure count carried into the next attempt: a returned network starts the backoff over. */
        fun failuresAfterWait(failures: Int, networkReturned: Boolean): Int = if (networkReturned) 0 else failures
    }
}

/**
 * Turns default-network callback updates into "the default network just
 * became validated" events. Capabilities are re-reported for bandwidth and
 * signal changes, so only a transition counts: a new default network
 * becoming validated (including switching back to one used before, which
 * gets no onLost when it merely stops being the default), or the same one
 * being validated again.
 */
internal class DefaultNetworkValidation {
    private var current: Any? = null
    private var validated = false

    /** Returns true when the default network has just become validated. */
    @Synchronized
    fun onCapabilities(network: Any, isValidated: Boolean): Boolean {
        if (network != current) {
            current = network
            validated = false
        }
        val becameValidated = isValidated && !validated
        validated = isValidated
        return becameValidated
    }

    @Synchronized
    fun onLost(network: Any) {
        if (network == current) {
            current = null
            validated = false
        }
    }
}
