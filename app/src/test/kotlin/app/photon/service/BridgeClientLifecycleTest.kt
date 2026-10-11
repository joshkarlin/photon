package app.photon.service

import app.photon.data.model.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Starting and stopping the bridge from several places at once. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class BridgeClientLifecycleTest {

    // Reader threads other tests left behind aren't this test's business.
    private val earlierReaders = readers()

    private fun readers() = Thread.getAllStackTraces().keys.filter { it.name == "gobridge-stdout" && it.isAlive }.toSet()

    private fun readerThreads() = (readers() - earlierReaders).size

    @Test
    fun concurrentConnectsAndDisconnectsLeaveOneBridgeWithOneReader() = runBlocking {
        val launched = CopyOnWriteArrayList<FakeProcess>()
        val client = BridgeClient(GoBridge({ FakeProcess().also { launched += it } }, exitGraceMs = 500))
        val failures = CopyOnWriteArrayList<Throwable>()

        // The supervisor, the settings page and the service's teardown can
        // all start or stop the bridge at the same moment.
        coroutineScope {
            repeat(8) { worker ->
                launch(Dispatchers.IO) {
                    repeat(25) { i ->
                        try {
                            if ((worker + i) % 2 == 0) client.connect() else client.disconnect()
                        } catch (e: Throwable) {
                            failures += e
                        }
                    }
                }
            }
        }
        client.connect()

        assertEquals(emptyList(), failures.map { it.toString() })
        launched.forEachIndexed { i, p ->
            assertTrue(p.stdoutReaders.get() <= 1, "bridge $i had ${p.stdoutReaders.get()} readers")
        }
        val alive = launched.filter { it.isAlive }
        assertEquals(1, alive.size, "bridges left running")
        repeat(50) { if (readerThreads() > 1) delay(20) }
        assertEquals(1, readerThreads(), "stdout reader threads")

        // Connected to the live bridge, and still so once stale readers wind down.
        delay(200)
        assertTrue(client.isConnected.value)
        val bridge = alive.single()
        thread(isDaemon = true) {
            bridge.sent.forEachLine { line ->
                val id = Json.parseToJsonElement(line).jsonObject["id"]!!.jsonPrimitive.content
                bridge.print("""{"id":"$id","type":"ping_response","payload":{"ok":"yes"}}""")
            }
        }
        assertEquals("yes", client.request("ping").string("ok"))
        client.disconnect()
    }

    @Test
    fun aStoppedBridgesLastOutputIsIgnored() = runBlocking {
        // On its way out the old bridge reports a state that is no longer
        // true by the time anyone reads it.
        val old = FakeProcess(hangsOnExit = true) {
            it.print("""{"type":"connection_state","payload":{"state":"logged_out"}}""")
        }
        val replacement = FakeProcess()
        val processes = java.util.concurrent.LinkedBlockingQueue(listOf(old, replacement))
        val client = BridgeClient(GoBridge({ processes.poll() }, exitGraceMs = 300))
        client.connect()
        old.print("""{"type":"connection_state","payload":{"state":"connected"}}""")
        withTimeout(5_000) { client.whatsappState.first { it == "connected" } }

        client.whileStopped {}
        repeat(100) { if (readerThreads() > 1) delay(20) }

        assertEquals(1, readerThreads())
        assertEquals("connecting", client.whatsappState.value, "the stopped bridge's last word was believed")
        replacement.print("""{"type":"connection_state","payload":{"state":"connected"}}""")
        withTimeout(5_000) { client.whatsappState.first { it == "connected" } }
        client.disconnect()
    }
}
