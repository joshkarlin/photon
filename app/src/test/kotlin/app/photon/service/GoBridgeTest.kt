package app.photon.service

import android.os.Looper
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GoBridgeTest {

    private fun flag(args: List<String>, name: String): String? =
        args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

    @Test
    fun retentionIsPassedOnSpawn() {
        val args = bridgeArgs("/lib/libgobridge.so", "/data/files", maxMessages = 200, maxDays = 30)
        assertEquals("/lib/libgobridge.so", args.first())
        assertEquals("/data/files", flag(args, "--data-dir"))
        assertEquals("200", flag(args, "--max-messages"))
        assertEquals("30", flag(args, "--max-days"))
        assertEquals(GoBridge.MEDIA_TTL_MINS.toString(), flag(args, "--media-ttl-mins"))
    }

    @Test
    fun restartBackoffDoublesToACapAndResets() {
        val backoff = RestartBackoff()
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000), List(8) { backoff.next() })
        backoff.reset()
        assertEquals(1_000L, backoff.next())
    }
}

/** Stopping the bridge process, against fake processes. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GoBridgeStopTest {

    @Test
    fun aBridgeThatExitsOnStdinCloseIsNotKilled() {
        val process = FakeProcess()
        val bridge = GoBridge({ process }, exitGraceMs = 2_000)
        runBlocking { bridge.start() }

        bridge.stop()

        assertFalse(process.isAlive)
        assertFalse(process.killed)
        assertFalse(bridge.isRunning())
    }

    @Test
    fun aBridgeStuckDisconnectingIsKilled() {
        val process = FakeProcess(hangsOnExit = true)
        val bridge = GoBridge({ process }, exitGraceMs = 200)
        runBlocking { bridge.start() }

        val started = System.nanoTime()
        bridge.stop()
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(process.killed)
        assertFalse(process.isAlive, "stop() returned while the old bridge was still running")
        assertTrue(tookMs < 2_000, "stop() took ${tookMs}ms")
    }

    @Test
    fun whileStoppedWaitsOffTheMainThreadAndStartsANewBridge() {
        val processes = LinkedBlockingQueue(listOf(FakeProcess(hangsOnExit = true), FakeProcess()))
        val first = processes.peek()!!
        val client = BridgeClient(GoBridge({ processes.poll() }, exitGraceMs = 200))
        runBlocking { client.connect() }
        assertTrue(client.isConnected.value)

        var blockThread: Thread? = null
        var oldBridgeAliveInBlock = true
        runBlocking {
            client.whileStopped {
                blockThread = Thread.currentThread()
                oldBridgeAliveInBlock = first.isAlive
            }
        }

        assertNotEquals(Looper.getMainLooper().thread, blockThread, "whileStopped ran on the main thread")
        assertFalse(oldBridgeAliveInBlock, "the block ran while the old bridge was alive")
        assertTrue(first.killed)
        assertTrue(client.isConnected.value, "no new bridge after whileStopped")
        assertTrue(processes.isEmpty(), "the replacement bridge was never started")
    }
}
