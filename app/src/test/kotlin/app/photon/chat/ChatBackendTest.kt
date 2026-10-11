package app.photon.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.photon.data.db.PhotonDatabase
import app.photon.data.db.createWhatsAppSchema
import app.photon.data.db.writeWhatsAppDb
import app.photon.data.names.LocalContacts
import app.photon.data.repository.ChatRepository
import app.photon.service.BridgeClient
import app.photon.service.GoBridge
import app.photon.signal.SignalRepository
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.store.PhotonIdentityKeyStore
import app.photon.signal.store.SignalProtocolDatabase
import app.photon.sms.SmsRepository
import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.Pipe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Each platform keeps the one error contract: a result, or a ChatBackendException. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ChatBackendTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val contacts = LocalContacts(context) { null }
    private val closeables = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() {
        closeables.forEach { it.close() }
        scope.cancel()
    }

    // ─── backendCall ────────────────────────────────────────────────

    @Test
    fun anyFailureBecomesABackendExceptionWithItsMessage() = runBlocking<Unit> {
        val e = assertFailsWith<ChatBackendException> {
            backendCall("Send") { throw IllegalStateException("WhatsApp bridge not running") }
        }
        assertEquals("WhatsApp bridge not running", e.message)
        assertFailsWith<ChatBackendException> {
            backendCall("Send") { withTimeout(1) { delay(1_000) } }
        }
        assertFailsWith<CancellationException> {
            backendCall("Send") { throw CancellationException("screen closed") }
        }.also { assertFalse(it is TimeoutCancellationException) }
    }

    // ─── WhatsApp ───────────────────────────────────────────────────

    /** Each request the fake bridge was sent: its type and payload. */
    private val bridgeRequests = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()

    /** A backend over a bridge that answers each request with [respond]'s payload. */
    private fun whatsApp(respond: (type: String) -> String): WhatsAppBackend {
        writeWhatsAppDb(File(context.filesDir, "messages.db")) { db ->
            createWhatsAppSchema(db)
            for ((jid, ts) in listOf("a@s.whatsapp.net" to 2, "status@broadcast" to 3, "b@s.whatsapp.net" to 0)) {
                db.execSQL("INSERT INTO conversations (jid, name, last_timestamp) VALUES (?, 'x', ?)", arrayOf<Any>(jid, ts))
                db.execSQL(
                    "INSERT INTO messages (id, conversation_jid, sender_jid, timestamp, content_type) VALUES (?, ?, ?, 1, 'text')",
                    arrayOf<Any>("m-$jid", jid, jid),
                )
            }
        }
        val client = BridgeClient(GoBridge({ null }))
        val toClient = Pipe.open()
        val toBridge = Pipe.open()
        val out = Channels.newOutputStream(toClient.sink()).bufferedWriter()
        closeables += out
        client.attach(Channels.newInputStream(toClient.source()), Channels.newOutputStream(toBridge.sink()))
        thread(isDaemon = true) {
            try {
                Channels.newInputStream(toBridge.source()).bufferedReader().forEachLine { line ->
                    val request = Json.parseToJsonElement(line).jsonObject
                    val id = request["id"]!!.jsonPrimitive.content
                    val type = request["type"]!!.jsonPrimitive.content
                    bridgeRequests += type to request["payload"].toString()
                    synchronized(out) {
                        out.write("""{"id":"$id","type":"${type}_response","payload":${respond(type)}}""")
                        out.newLine()
                        out.flush()
                    }
                }
            } catch (_: IOException) {
            }
        }
        return WhatsAppBackend(ChatRepository(PhotonDatabase(context), client, scope, contacts), client, scope)
    }

    @Test
    fun whatsAppBridgeErrorsBecomeBackendExceptions() = runBlocking<Unit> {
        val backend = whatsApp { type ->
            when (type) {
                "download_media" -> """{"local_path":"/media/x.jpg"}"""
                "send_message" -> """{"message_id":"id1","timestamp":1}"""
                else -> """{"error":"media expired"}"""
            }
        }
        backend.sendText("a@s.whatsapp.net", "hi", null)
        assertEquals("/media/x.jpg", backend.downloadMedia("m1"))
        val e = assertFailsWith<ChatBackendException> { backend.markRead("a@s.whatsapp.net", listOf("m1")) }
        assertEquals("media expired", e.message)
        assertFailsWith<ChatBackendException> { backend.retry("m1") }
    }

    @Test
    fun whatsAppSendsADocumentUnderItsOwnName() = runBlocking<Unit> {
        val backend = whatsApp { """{"message_id":"id1","timestamp":1}""" }
        backend.sendMedia("a@s.whatsapp.net", "/files/send_media_1.pdf", "application/pdf", null, fileName = "Q1 report.pdf")
        val payload = Json.parseToJsonElement(bridgeRequests.single { it.first == "send_media" }.second).jsonObject
        assertEquals("Q1 report.pdf", payload["file_name"]?.jsonPrimitive?.content)
        assertEquals("/files/send_media_1.pdf", payload["file_path"]?.jsonPrimitive?.content)
    }

    @Test
    fun whatsAppDownloadWithoutAPathIsABackendException() = runBlocking<Unit> {
        val backend = whatsApp { "{}" }
        val e = assertFailsWith<ChatBackendException> { backend.downloadMedia("m1") }
        assertEquals("Download not available", e.message)
    }

    @Test
    fun whatsAppListsOnlyRealChatsWithActivity() = runBlocking<Unit> {
        val backend = whatsApp { "{}" }
        val listed = withTimeout(5_000) { backend.conversations.filterNotNull().first() }
        assertEquals(listOf("a@s.whatsapp.net"), listed.map { it.jid })
    }

    // ─── Signal ─────────────────────────────────────────────────────

    private fun signal(): SignalBackend {
        val db = SignalMessageDatabase(context).also { closeables += it }
        val protocolDb = SignalProtocolDatabase(context).also { closeables += it }
        // No sender: every send reports it didn't go out.
        val repository = SignalRepository(db, null, scope, contacts) { null }
        return SignalBackend(repository, MutableStateFlow("disconnected"), PhotonIdentityKeyStore(protocolDb)) { null }
    }

    @Test
    fun signalSendsThatDidntGoOutAreBackendExceptions() = runBlocking<Unit> {
        val backend = signal()
        assertFailsWith<ChatBackendException> { backend.sendText("alice", "hi", null) }
        assertFailsWith<ChatBackendException> { backend.retry("x") }
        assertFailsWith<ChatBackendException> { backend.delete("alice", "x", forEveryone = true) }
        assertEquals("Download not available", assertFailsWith<ChatBackendException> { backend.downloadMedia("x") }.message)
        // Nothing waiting to sync: marking read succeeds without a sender.
        backend.markRead("alice", listOf("x"))
    }

    // ─── SMS ────────────────────────────────────────────────────────

    @Test
    fun smsRefusesWhatItCantDo() = runBlocking<Unit> {
        val backend = SmsBackend(SmsRepository(context, scope, contacts))
        val caps = backend.capabilities
        assertFalse(caps.reply || caps.media || caps.audio || caps.reactions || caps.delete || caps.deleteForEveryone)
        assertTrue(caps.contactEditing)
        assertFailsWith<ChatBackendException> { backend.sendMedia("+447700900123", "/x.jpg", "image/jpeg", null) }
        assertFailsWith<ChatBackendException> { backend.react("+447700900123", "1", "me", "👍") }
        assertFailsWith<ChatBackendException> { backend.delete("+447700900123", "1", forEveryone = false) }
        assertEquals("+447700900123", backend.contactPhone("+447700900123"))
        assertEquals(null, backend.contactPhone("HSBC UK"))
        assertFalse(backend.mayHaveContactPhone("HSBC UK"), "an alphanumeric sender's title isn't tappable")
    }
}
