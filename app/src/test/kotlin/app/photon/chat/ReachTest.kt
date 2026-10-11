package app.photon.chat

import android.content.Context
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import app.photon.data.db.PhotonDatabase
import app.photon.data.db.createWhatsAppSchema
import app.photon.data.db.writeWhatsAppDb
import app.photon.data.names.DisplayNames
import app.photon.data.names.LocalContacts
import app.photon.data.repository.ChatRepository
import app.photon.service.BridgeClient
import app.photon.service.GoBridge
import app.photon.signal.SignalRepository
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.insertMessage
import app.photon.signal.db.updateContactFromSync
import app.photon.signal.db.upsertConversation
import app.photon.signal.store.PhotonIdentityKeyStore
import app.photon.signal.store.SignalProtocolDatabase
import app.photon.sms.FakeSmsProvider
import app.photon.sms.SmsRepository
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import kotlin.concurrent.thread
import kotlin.test.assertEquals

/** Where each platform can reach a number, for New message. Nothing is sent anywhere. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ReachTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val contacts = LocalContacts(context) { null }
    private val closeables = mutableListOf<AutoCloseable>()
    private var savedCountry = ""

    @Before
    fun setCountry() {
        savedCountry = DisplayNames.defaultCountry
        DisplayNames.defaultCountry = "GB"
    }

    @After
    fun tearDown() {
        DisplayNames.defaultCountry = savedCountry
        closeables.forEach { it.close() }
        scope.cancel()
    }

    // ─── WhatsApp ───────────────────────────────────────────────────

    /** A bridge on a pair of pipes that answers check_on_whatsapp with [answer] and records what it was asked. */
    private inner class FakeBridge(private val answer: (payload: String) -> String) {
        val client = BridgeClient(GoBridge({ null }))
        val asked = CopyOnWriteArrayList<String>()
        private val out: BufferedWriter

        init {
            val toClient = Pipe.open()
            val toBridge = Pipe.open()
            out = Channels.newOutputStream(toClient.sink()).bufferedWriter()
            closeables += out
            client.attach(Channels.newInputStream(toClient.source()), Channels.newOutputStream(toBridge.sink()))
            thread(isDaemon = true) {
                try {
                    Channels.newInputStream(toBridge.source()).bufferedReader().forEachLine { line ->
                        val request = Json.parseToJsonElement(line).jsonObject
                        val id = request["id"]!!.jsonPrimitive.content
                        val type = request["type"]!!.jsonPrimitive.content
                        val payload = request["payload"].toString()
                        asked += "$type $payload"
                        print("""{"id":"$id","type":"${type}_response","payload":${answer(payload)}}""")
                    }
                } catch (_: IOException) {
                }
            }
        }

        fun print(line: String) = synchronized(out) {
            out.write(line)
            out.newLine()
            out.flush()
        }

        fun state(state: String) = runBlocking {
            print("""{"type":"connection_state","payload":{"state":"$state"}}""")
            withTimeout(5_000) { client.whatsappState.first { it == state } }
        }
    }

    private fun whatsApp(bridge: FakeBridge, chats: List<String> = emptyList()): WhatsAppBackend {
        writeWhatsAppDb(File(context.filesDir, "messages.db")) { db ->
            createWhatsAppSchema(db)
            for (jid in chats) {
                db.execSQL("INSERT INTO conversations (jid, name, last_timestamp) VALUES (?, 'Mo', 5)", arrayOf<Any>(jid))
            }
        }
        return WhatsAppBackend(ChatRepository(PhotonDatabase(context), bridge.client, scope, contacts), bridge.client, scope)
    }

    @Test
    fun whatsAppOpensAChatUnderTheNumberWithoutAsking() = runBlocking<Unit> {
        val bridge = FakeBridge { error("not asked") }
        val backend = whatsApp(bridge, chats = listOf("447700900123@s.whatsapp.net"))
        // Offline: the chat is known, so it still opens.
        assertEquals(Reach.Reachable("447700900123@s.whatsapp.net", existing = true), backend.reach("+447700900123"))
        assertEquals(emptyList(), bridge.asked)
    }

    @Test
    fun whatsAppAsksTheBridgeAboutANewNumber() = runBlocking<Unit> {
        val bridge = FakeBridge { payload ->
            if ("+447700900123" in payload) {
                """{"results":[{"phone":"+447700900123","jid":"447700900123@s.whatsapp.net","on_whatsapp":true,"existing":false}]}"""
            } else {
                """{"results":[{"phone":"+447700900999","on_whatsapp":false,"existing":false}]}"""
            }
        }
        val backend = whatsApp(bridge)
        bridge.state("connected")

        assertEquals(Reach.Reachable("447700900123@s.whatsapp.net", existing = false), backend.reach("+447700900123"))
        assertEquals(Reach.Unreachable("not on WhatsApp"), backend.reach("+447700900999"))
        assertEquals("""check_on_whatsapp {"phones":["+447700900123"]}""", bridge.asked.first())
    }

    @Test
    fun whatsAppReportsALIDChatTheBridgeFound() = runBlocking<Unit> {
        val bridge = FakeBridge {
            """{"results":[{"phone":"+447700900123","jid":"447700900123@s.whatsapp.net","on_whatsapp":true,"existing":true}]}"""
        }
        val backend = whatsApp(bridge)
        bridge.state("connected")
        assertEquals(Reach.Reachable("447700900123@s.whatsapp.net", existing = true), backend.reach("+447700900123"))
    }

    /** Offline, whether a number is on WhatsApp is unknown: unavailable, with why. */
    @Test
    fun whatsAppOfflineIsUnavailableWithTheReason() = runBlocking<Unit> {
        val bridge = FakeBridge { error("not asked") }
        val backend = whatsApp(bridge)

        assertEquals(Reach.Unreachable("WhatsApp disconnected"), backend.reach("+447700900123"))
        bridge.state("logged_out")
        assertEquals(Reach.Unreachable("WhatsApp not linked"), backend.reach("+447700900123"))
        assertEquals(emptyList(), bridge.asked)
    }

    @Test
    fun whatsAppBridgeErrorsAreUnavailable() = runBlocking<Unit> {
        var error = "WhatsApp disconnected"
        val bridge = FakeBridge { """{"error":"$error"}""" }
        val backend = whatsApp(bridge)
        bridge.state("connected")

        // WhatsApp dropped between the check here and the bridge's.
        assertEquals(Reach.Unreachable("WhatsApp disconnected"), backend.reach("+447700900123"))
        error = "usync timed out"
        assertEquals(Reach.Unreachable("couldn't check WhatsApp"), backend.reach("+447700900123"))
    }

    // ─── Signal ─────────────────────────────────────────────────────

    private val aci = "00000000-0000-4000-8000-00000000a11c"

    private fun signal(): Pair<SignalBackend, SignalMessageDatabase> {
        val db = SignalMessageDatabase(context).also { closeables += it }
        val protocolDb = SignalProtocolDatabase(context).also { closeables += it }
        val repository = SignalRepository(db, null, scope, contacts) { null }
        return SignalBackend(repository, MutableStateFlow("connected"), PhotonIdentityKeyStore(protocolDb)) { null } to db
    }

    @Test
    fun signalReachesOnlyNumbersItKnows() = runBlocking<Unit> {
        val (backend, db) = signal()
        db.updateContactFromSync(aci, "+447700900123", "Mo")

        assertEquals(Reach.Reachable(aci, existing = false), backend.reach("+447700900123"))
        assertEquals(Reach.Unreachable("not known to Signal"), backend.reach("+447700900999"))

        db.upsertConversation(jid = aci, name = null, isGroup = false)
        db.insertMessage(id = "${aci}_1", conversationJid = aci, senderJid = aci, timestamp = 1, contentType = "text", textBody = "hi")
        assertEquals(Reach.Reachable(aci, existing = true), backend.reach("+447700900123"))
    }

    @Test
    fun signalDoesntReachAPniByItself() = runBlocking<Unit> {
        val (backend, db) = signal()
        db.updateContactFromSync("PNI:00000000-0000-4000-8000-0000000000b0", "+447700900123", null)
        assertEquals(Reach.Unreachable("not known to Signal"), backend.reach("+447700900123"))
    }

    // ─── SMS ────────────────────────────────────────────────────────

    @Test
    fun smsReachesAnyNumberAndFindsItsChatHoweverItWasWritten() = runBlocking<Unit> {
        val provider = FakeSmsProvider.install()
        provider.add("07400 123456", "Dinner?", 3_000, Telephony.Sms.MESSAGE_TYPE_INBOX, thread = 1)
        val backend = SmsBackend(SmsRepository(context, scope, contacts))

        assertEquals(Reach.Reachable("07400123456", existing = true), backend.reach("+447400123456"))
        assertEquals(Reach.Reachable("+447700900123", existing = false), backend.reach("+447700900123"))
        assertEquals(Reach.Unreachable("not a phone number"), backend.reach("HSBC UK"))
    }
}
