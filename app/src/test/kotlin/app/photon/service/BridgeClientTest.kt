package app.photon.service

import androidx.test.core.app.ApplicationProvider
import app.photon.data.model.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedWriter
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.Pipe
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BridgeClient against a fake bridge on a pair of pipes. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class BridgeClientTest {
    private lateinit var client: BridgeClient
    private lateinit var bridgeOut: BufferedWriter      // what the "bridge" prints
    private lateinit var bridgeIn: java.io.BufferedReader // what the client sent

    @Before
    fun setUp() {
        client = BridgeClient(GoBridge(ApplicationProvider.getApplicationContext()))
        val toClient = Pipe.open()
        val toBridge = Pipe.open()
        bridgeOut = Channels.newOutputStream(toClient.sink()).bufferedWriter()
        bridgeIn = Channels.newInputStream(toBridge.source()).bufferedReader()
        client.attach(Channels.newInputStream(toClient.source()), Channels.newOutputStream(toBridge.sink()))
    }

    @After
    fun tearDown() {
        try { bridgeOut.close() } catch (_: IOException) {}
    }

    private fun bridgePrints(line: String) {
        synchronized(bridgeOut) {
            bridgeOut.write(line)
            bridgeOut.write("\n")
            bridgeOut.flush()
        }
    }

    @Test
    fun requestGetsTheResponseWithItsId() = runBlocking {
        // Answer each request line with its id, echoing the type.
        thread(isDaemon = true) {
            bridgeIn.forEachLine { line ->
                val req = Json.parseToJsonElement(line).jsonObject
                val id = req["id"]!!.jsonPrimitive.content
                val type = req["type"]!!.jsonPrimitive.content
                bridgePrints("""{"id":"$id","type":"${type}_response","payload":{"echo":"$type"}}""")
            }
        }
        val responses = List(20) { i -> async { client.request("ping_$i") } }.map { it.await() }
        responses.forEachIndexed { i, resp -> assertEquals("ping_$i", resp.string("echo")) }
    }

    @Test
    fun losslessSubscribersGetEveryEventOfTheirTypes() = runBlocking {
        val channel = client.subscribeLossless(setOf("new_message"))
        thread {
            repeat(1_000) { i ->
                bridgePrints("""{"type":"typing","payload":{}}""")
                bridgePrints("""{"type":"new_message","payload":{"message_id":"m$i"}}""")
            }
        }
        // Nobody reads the UI flow and this consumer is slow to start, yet
        // nothing is dropped.
        val ids = withTimeout(10_000) { List(1_000) { channel.receive().string("message_id") } }
        assertEquals(List(1_000) { "m$it" }, ids)
    }

    @Test
    fun connectionStateIsTracked() = runBlocking {
        bridgePrints("""{"type":"connection_state","payload":{"state":"connected"}}""")
        withTimeout(5_000) { client.whatsappState.first { it == "connected" } }
        Unit
    }

    @Test
    fun bridgeExitFailsPendingRequestsAndDisconnects() = runBlocking {
        assertTrue(client.isConnected.value)
        val pending = async(Dispatchers.IO) { runCatching { client.request("send_message") } }
        withContext(Dispatchers.IO) { bridgeIn.readLine() } // the request went out...
        bridgeOut.close() // ...and the bridge dies before answering

        val result = withTimeout(5_000) { pending.await() }
        assertTrue(result.exceptionOrNull() is IOException, "pending request failed with ${result.exceptionOrNull()}")
        withTimeout(5_000) { client.isConnected.first { !it } }
        assertFalse(client.isConnected.value)
        assertEquals("connecting", client.whatsappState.value)
        assertFailsWith<IllegalStateException> { client.request("send_message") }
        Unit
    }
}
