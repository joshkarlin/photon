package app.photon.signal.receiver

import android.util.Base64
import app.photon.signal.EarlyReadCache
import app.photon.signal.db.StorageMute
import app.photon.signal.db.applyStorageManifest
import app.photon.signal.db.getConversation
import app.photon.signal.db.upsertConversation
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.api.messages.EnvelopeResponse
import org.whispersystems.signalservice.internal.push.CallMessage
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.Envelope
import org.whispersystems.signalservice.internal.websocket.WebSocketRequestMessage
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A chat muted on the primary posts no message or group-call notification
 * (its messages still count unread), but calls ring and show missed as
 * usual. A timed mute lapses on its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MutedNotificationTest {

    private val local = "00000000-0000-4000-8000-00000000000a"
    private val alice = "00000000-0000-4000-8000-00000000a11c"
    private val groupId = ByteArray(32) { it.toByte() }
    private val group = Base64.encodeToString(groupId, Base64.NO_WRAP or Base64.URL_SAFE)
    private val t0 = 1_700_000_000_000L

    private lateinit var fx: ReceiverFixture
    private lateinit var handler: DataMessageHandler
    private lateinit var calls: CallHandler
    private var now = t0

    @Before
    fun setUp() {
        fx = ReceiverFixture(local)
        val notifications = MessageNotifications(fx.db, fx.names, fx.notifier, clock = { now })
        calls = CallHandler(
            fx.credentials, fx.db, fx.contactNames, fx.names, fx.notifier,
            schedule = { _, _ -> }, clock = { now },
        )
        handler = DataMessageHandler(
            fx.db, fx.contactNames, fx.groupMetadata, calls, RemoteEdits(fx.db, notifications),
            Attachments(fx.context, fx.db) { null }, EarlyReadCache(), notifications, fx.scope,
        )
        fx.db.upsertConversation(jid = group, name = "Climbing", isGroup = true)
    }

    @After
    fun tearDown() = fx.close()

    private fun mute(jid: String, until: Long) =
        fx.db.applyStorageManifest(1, emptyList(), listOf(StorageMute("s-$jid", jid, until)))

    private fun metadata(groupId: ByteArray? = null) =
        EnvelopeMetadata(ServiceId.ACI.parseOrNull(alice)!!, null, 1, true, groupId, ServiceId.ACI.parseOrNull(local)!!, 0)

    private fun receive(body: String, groupId: ByteArray? = null) {
        now += 1_000
        handler.processDataMessage(DataMessage(body = body, timestamp = now), metadata(groupId))
    }

    @Test
    fun aMutedDmCountsUnreadWithoutNotifying() {
        mute(alice, -1)

        receive("hi")
        receive("you there?")

        assertEquals(emptyList(), fx.notifier.events)
        assertEquals(2, fx.db.getConversation(alice)?.unreadCount)
    }

    @Test
    fun aMutedGroupPostsNeitherMessagesNorGroupCalls() {
        mute(group, -1)

        receive("see you at 8", groupId)
        now += 1_000
        handler.processDataMessage(
            DataMessage(timestamp = now, groupCallUpdate = DataMessage.GroupCallUpdate(eraId = "era-1")),
            metadata(groupId),
        )

        assertEquals(emptyList(), fx.notifier.events)
        assertEquals(2, fx.db.getConversation(group)?.unreadCount)
    }

    /**
     * A group-call start the server held longer than a ringing call lasts
     * (Photon was offline) is recorded, unread, without alerting.
     */
    @Test
    fun aLateGroupCallStartIsRecordedWithoutAlerting() {
        val serverGot = System.currentTimeMillis() - 10 * 60_000
        handler.processDataMessage(
            DataMessage(timestamp = now, groupCallUpdate = DataMessage.GroupCallUpdate(eraId = "era-3")),
            metadata(groupId),
            serverReceivedMs = serverGot,
            serverDeliveredMs = System.currentTimeMillis(),
        )

        assertEquals(emptyList(), fx.notifier.events)
        assertEquals(1, fx.db.getConversation(group)?.unreadCount)

        // One the server passed on promptly still alerts.
        handler.processDataMessage(
            DataMessage(timestamp = now + 1, groupCallUpdate = DataMessage.GroupCallUpdate(eraId = "era-4")),
            metadata(groupId),
            serverReceivedMs = System.currentTimeMillis() - 5_000,
            serverDeliveredMs = System.currentTimeMillis(),
        )
        assertEquals(1, fx.notifier.events.size)
    }

    /** A group call starting is announced at the Calls level, not as a message. */
    @Test
    fun aGroupCallStartingAlertsAsACall() {
        handler.processDataMessage(
            DataMessage(timestamp = now, groupCallUpdate = DataMessage.GroupCallUpdate(eraId = "era-2")),
            metadata(groupId),
        )

        assertEquals(1, fx.notifier.events.size)
        assertTrue(fx.notifier.events.single().startsWith("groupCall:$group:Climbing:"))
    }

    @Test
    fun aTimedMuteLapses() {
        mute(alice, now / 1000 + 3600)

        receive("muted")
        now += 3_601_000
        receive("not any more")

        assertEquals(listOf("message:$alice:Signal contact:not any more"), fx.notifier.events)
    }

    @Test
    fun otherChatsStillNotify() {
        mute(group, -1)

        receive("hi")

        assertEquals(1, fx.notifier.events.size)
    }

    @Test
    fun aMutedChatStillRingsAndShowsTheMissedCall() {
        mute(alice, -1)

        calls.processCallMessage(
            CallMessage(offer = CallMessage.Offer(id = 7, type = CallMessage.Offer.Type.OFFER_AUDIO_CALL)),
            metadata(),
            EnvelopeResponse(Envelope(clientTimestamp = now, serverTimestamp = now), now, WebSocketRequestMessage()),
        )
        calls.processCallMessage(
            CallMessage(hangup = CallMessage.Hangup(id = 7, type = CallMessage.Hangup.Type.HANGUP_NORMAL)),
            metadata(),
            EnvelopeResponse(Envelope(clientTimestamp = now, serverTimestamp = now), now, WebSocketRequestMessage()),
        )

        assertEquals(listOf("ringing:$alice", "missed:$alice"), fx.notifier.events)
    }
}
