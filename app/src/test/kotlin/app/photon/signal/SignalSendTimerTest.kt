package app.photon.signal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.getMessage
import app.photon.signal.db.getMessages
import app.photon.signal.db.insertOwnRows
import app.photon.signal.db.updateDmTimer
import app.photon.signal.db.updateGroupMeta
import app.photon.signal.db.updateGroupTimer
import app.photon.signal.db.upsertConversation
import app.photon.signal.store.PhotonProtocolStore
import app.photon.signal.store.SignalProtocolDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.messages.SignalServiceGroupV2
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import java.io.File
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Photon follows a chat's disappearing-messages timer and never changes it:
 * every message it sends, and the transcript its other devices get, carries
 * the chat's timer and version, and its own copy disappears after it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SignalSendTimerTest {

    private val me = "00000000-0000-4000-8000-00000000000a"
    private val alice = "00000000-0000-4000-8000-00000000a11c"
    private val group = "Z3JvdXAtaWQtZm9yLXRlc3RzLTMyLWJ5dGVzLWxvbmc="
    private val masterKey = ByteArray(32) { 9 }
    private val week = 604_800

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: SignalMessageDatabase
    private lateinit var protocolDb: SignalProtocolDatabase
    private val transport = FakeTransport()
    private lateinit var sender: SignalMessageSender

    @Before
    fun setUp() {
        db = SignalMessageDatabase(context)
        protocolDb = SignalProtocolDatabase(context)
        val credentials = SignalCredentials(protocolDb).apply { aciString = me }
        sender = SignalMessageSender(credentials, PhotonProtocolStore(protocolDb), db, transport)
        db.upsertConversation(jid = alice, name = null, isGroup = false)
        db.upsertConversation(jid = group, name = "Group", isGroup = true)
        db.updateGroupMeta(group, masterKey, 4)
    }

    @After
    fun tearDown() {
        sender.shutdown()
        db.close()
        protocolDb.close()
    }

    private fun sentTimer() = assertIs<OutgoingContent.Message>(transport.sent.last().content).timer

    private fun expiry(id: String): Pair<Long, Long> =
        db.readableDatabase.rawQuery("SELECT expires_in, expires_at FROM messages WHERE id = ?", arrayOf(id))
            .use { it.moveToFirst(); it.getLong(0) to it.getLong(1) }

    /** The version-1 case: the stored timer at version 1 goes out, not "off at version 1". */
    @Test
    fun aTextCarriesTheChatsTimerAndVersion() {
        db.updateDmTimer(alice, 0) { ChatTimer(week, 1) }

        sender.sendTextMessage(alice, "hello")

        assertEquals(ChatTimer(week, 1), sentTimer())
        val row = db.getMessages(alice).first { it.contentType == "text" }
        val (expiresIn, expiresAt) = expiry(row.id)
        assertEquals(week.toLong(), expiresIn)
        assertTrue(expiresAt > System.currentTimeMillis())
    }

    @Test
    fun aChatWithoutATimerSendsOffAtItsVersion() {
        db.updateDmTimer(alice, 0) { ChatTimer(0, 4) }

        sender.sendTextMessage(alice, "hello")

        assertEquals(ChatTimer(0, 4), sentTimer())
        assertEquals(0L to 0L, expiry(db.getMessages(alice).first { it.contentType == "text" }.id))
    }

    @Test
    fun mediaCarriesTheTimerToo() {
        db.updateDmTimer(alice, 0) { ChatTimer(300, 2) }
        val file = File(context.filesDir, "send_media_1.jpg").apply { writeBytes(ByteArray(10)) }

        sender.sendMediaMessage(alice, file.path, "image/jpeg", caption = null)

        assertEquals(ChatTimer(300, 2), sentTimer())
    }

    /** A group sends its state's timer, at version 1. */
    @Test
    fun aGroupMessageCarriesTheGroupsTimer() {
        db.updateGroupTimer(group, week, revision = 4)

        sender.sendTextMessage(group, "hi all")

        assertEquals(ChatTimer(week, 1), sentTimer())
    }

    /** No group state yet and no connection to fetch it: the send still goes, with what's stored. */
    @Test
    fun aGroupWhoseTimerIsUnknownStillSends() {
        assertTrue(sender.sendTextMessage(group, "hi all"))
        assertEquals(ChatTimer.DEFAULT, sentTimer())
    }

    /** A failed message isn't counting down; its retry sends with the timer as it is by then. */
    @Test
    fun aRetrySendsWithTheChatsTimerNow() {
        transport.failSends = true
        sender.sendTextMessage(alice, "hello")
        val id = db.getMessages(alice).single().id
        assertEquals(0L to 0L, expiry(id))

        db.updateDmTimer(alice, 0) { ChatTimer(3_600, 3) }
        transport.failSends = false
        assertTrue(sender.retryMessage(id))

        assertEquals(ChatTimer(3_600, 3), sentTimer())
        assertEquals(3_600L, expiry(id).first)
        assertTrue(expiry(id).second > 0)
    }

    /** Reactions and deletes go without a timer, as Signal-Android sends them. */
    @Test
    fun reactionsAndDeletesCarryNoTimer() {
        db.updateDmTimer(alice, 0) { ChatTimer(week, 2) }
        val message = outgoingDataMessage(
            OutgoingContent.Reaction(SignalServiceDataMessage.Reaction("👍", false, ServiceId.ACI.parseOrNull(alice)!!, 1)),
            1_700_000_000_000, group = null,
        ) { null }

        assertEquals(0, message.expiresInSeconds)
        assertFalse(message.isExpirationUpdate)
    }

    // ── What goes on the wire, and to the user's other devices ──

    @Test
    fun theDataMessageAndItsTranscriptCarryTheTimerAndVersion() {
        val message = outgoingDataMessage(
            OutgoingContent.Message("hello", null, timer = ChatTimer(week, 3)),
            1_700_000_000_000, group = null,
        ) { null }

        assertEquals(week, message.expiresInSeconds)
        assertEquals(3, message.expireTimerVersion)
        assertFalse(message.isExpirationUpdate)

        val transcript = sentTranscript(
            Optional.of(SignalServiceAddress(ServiceId.ACI.parseOrNull(alice)!!)), 1_700_000_000_000, message,
            listOf(ServiceId.ACI.parseOrNull(alice)!!),
        )
        val synced = transcript.dataMessage.get()
        assertEquals(week, synced.expiresInSeconds)
        assertEquals(3, synced.expireTimerVersion)
        assertEquals(setOf<ServiceId>(ServiceId.ACI.parseOrNull(alice)!!), transcript.recipients)
        assertFalse(transcript.isUnidentified(ServiceId.ACI.parseOrNull(alice)!!))
    }

    @Test
    fun aGroupTranscriptCarriesTheGroupsTimer() {
        val groupContext = SignalServiceGroupV2.newBuilder(GroupMasterKey(masterKey)).withRevision(4).build()
        val message = outgoingDataMessage(
            OutgoingContent.Message("hi all", null, timer = ChatTimer(week, 1)),
            1_700_000_000_000, groupContext,
        ) { null }

        val synced = sentTranscript(Optional.empty(), 1_700_000_000_000, message, emptyList()).dataMessage.get()
        assertEquals(week, synced.expiresInSeconds)
        assertEquals(1, synced.expireTimerVersion)
        assertTrue(synced.groupContext.isPresent)
    }

    /** Delete for everyone: every part of a message sent with several attachments goes here too. */
    @Test
    fun deleteForEveryoneTakesEveryPart() {
        val id = "${me}_1700000000456_s"
        db.insertOwnRows(
            id, listOf(PlannedRow("image", "a"), PlannedRow("image", null)), alice, me, 1_700_000_000,
        )

        assertTrue(sender.deleteMessage(alice, id, forEveryone = true))

        assertEquals(emptyList(), db.getMessages(alice))
        assertEquals(null, db.getMessage("$id.2"))
    }
}
