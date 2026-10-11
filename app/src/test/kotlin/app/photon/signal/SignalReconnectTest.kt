package app.photon.signal

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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

    /**
     * After an outage the backoff can be at its 5-minute cap. A validated
     * network must end that wait at once and start the backoff over, or
     * messages (and calls) arriving meanwhile are only seen minutes later.
     */
    @Test
    fun aValidatedNetworkEndsTheBackoffAndStartsItOver() = runBlocking {
        val network = NetworkReturn()
        val failures = 7
        val epoch = network.epoch
        launch {
            yield()
            network.networkValidated()
        }

        val started = System.nanoTime()
        val returned = network.await(SignalReconnect.delayMs(failures), epoch)

        assertTrue(returned)
        assertTrue(System.nanoTime() - started < 5_000_000_000L)
        val next = NetworkReturn.failuresAfterWait(failures, returned)
        assertEquals(0, next)
        // The next failure waits the base delay again.
        assertEquals(SignalReconnect.BASE_DELAY_MS, SignalReconnect.delayMs(SignalReconnect.nextFailureCount(next, null)))
    }

    /** The network came back while the failing attempt was still running: retry without waiting. */
    @Test
    fun aNetworkValidatedDuringTheAttemptSkipsTheWait() = runBlocking {
        val network = NetworkReturn()
        val epoch = network.epoch
        network.networkValidated()

        assertTrue(network.await(SignalReconnect.MAX_DELAY_MS, epoch))
    }

    @Test
    fun withoutANetworkChangeTheBackoffRunsItsCourse() = runBlocking {
        val network = NetworkReturn()
        network.networkValidated()

        assertFalse(network.await(20, network.epoch))
        assertEquals(6, NetworkReturn.failuresAfterWait(6, networkReturned = false))
    }

    /** Capabilities are re-reported for signal changes; only a newly validated network counts. */
    @Test
    fun onlyANewlyValidatedNetworkCounts() {
        val networks = ValidatedNetworks()

        assertTrue(networks.onCapabilities("wifi", isValidated = true))
        assertFalse(networks.onCapabilities("wifi", isValidated = true))
        assertFalse(networks.onCapabilities("wifi", isValidated = false))
        assertTrue(networks.onCapabilities("wifi", isValidated = true))
        networks.onLost("wifi")
        assertTrue(networks.onCapabilities("wifi", isValidated = true))
        assertTrue(networks.onCapabilities("cell", isValidated = true))
        assertFalse(networks.onCapabilities("cell", isValidated = false))
    }
}
