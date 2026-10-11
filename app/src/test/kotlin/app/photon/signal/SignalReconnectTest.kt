package app.photon.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalReconnectTest {

    /** Retries back off exponentially instead of hammering the server every 5s forever. */
    @Test
    fun backoffDoublesUpToFiveMinutes() {
        val delays = (1..9).map { SignalReconnect.delayMs(it) / 1000 }

        assertEquals(listOf(5L, 10L, 20L, 40L, 80L, 160L, 300L, 300L, 300L), delays)
        assertEquals(300_000L, SignalReconnect.delayMs(1_000))
    }

    @Test
    fun aStableConnectionResetsTheBackoff() {
        assertEquals(1, SignalReconnect.nextFailureCount(previous = 6, connectedForMs = 10 * 60_000L))
        assertEquals(7, SignalReconnect.nextFailureCount(previous = 6, connectedForMs = 2_000L))
        assertEquals(7, SignalReconnect.nextFailureCount(previous = 6, connectedForMs = null))
    }

    /** Upkeep runs on the first connect and then at most every 10 minutes. */
    @Test
    fun maintenanceIsThrottledAcrossReconnects() {
        val t = 1_700_000_000_000L
        assertTrue(SignalReconnect.maintenanceDue(lastRunMs = 0, nowMs = t))
        assertFalse(SignalReconnect.maintenanceDue(lastRunMs = t, nowMs = t + 30_000))
        assertTrue(SignalReconnect.maintenanceDue(lastRunMs = t, nowMs = t + 10 * 60_000L))
    }
}
