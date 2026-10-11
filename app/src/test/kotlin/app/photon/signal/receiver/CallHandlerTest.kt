package app.photon.signal.receiver

import app.photon.signal.SignalCalls
import app.photon.signal.db.getConversation
import app.photon.signal.db.getMessage
import app.photon.signal.db.upsertCallRow
import app.photon.signal.db.upsertConversation
import okio.ByteString.Companion.toByteString
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
import org.whispersystems.signalservice.internal.push.Envelope
import org.whispersystems.signalservice.internal.push.SyncMessage
import org.whispersystems.signalservice.internal.websocket.WebSocketRequestMessage
import kotlin.test.assertEquals

/**
 * A ringing call must end even when no hangup or call-event sync reaches
 * Photon: after the ring timeout it becomes a missed call, live and after a
 * restart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CallHandlerTest {

    private val local = "00000000-0000-4000-8000-00000000000a"
    private val peer = "00000000-0000-4000-8000-0000000ca11e"
    private val t0 = 1_700_000_000_000L

    private lateinit var fx: ReceiverFixture
    private lateinit var calls: CallHandler
    private var now = t0
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()

    @Before
    fun setUp() {
        fx = ReceiverFixture(local)
        calls = CallHandler(
            fx.credentials, fx.db, fx.contactNames, fx.groupMetadata, fx.notifier,
            schedule = { delayMs, task -> scheduled += delayMs to task },
            clock = { now },
        )
    }

    @After
    fun tearDown() = fx.close()

    private fun deliver(call: CallMessage, serverTs: Long = now) = calls.processCallMessage(
        call,
        EnvelopeMetadata(ServiceId.ACI.parseOrNull(peer)!!, null, 1, true, null, ServiceId.ACI.parseOrNull(local)!!, 0),
        EnvelopeResponse(Envelope(clientTimestamp = serverTs, serverTimestamp = serverTs), now, WebSocketRequestMessage()),
    )

    private fun offer(callId: Long) =
        deliver(CallMessage(offer = CallMessage.Offer(id = callId, type = CallMessage.Offer.Type.OFFER_AUDIO_CALL)))

    private fun runScheduled() {
        val tasks = scheduled.toList()
        scheduled.clear()
        tasks.forEach { it.second() }
    }

    private fun row(callId: Long) = fx.db.getMessage(SignalCalls.rowId(peer, callId))
    private fun unread() = fx.db.getConversation(peer)?.unreadCount

    @Test
    fun unansweredCallBecomesMissedAfterTheRingTimeout() {
        offer(42)

        assertEquals("Incoming voice call", row(42)?.textBody)
        assertEquals(0, unread())
        assertEquals(listOf(SignalCalls.RING_TIMEOUT_MS), scheduled.map { it.first })

        now += SignalCalls.RING_TIMEOUT_MS
        runScheduled()

        assertEquals("Missed voice call", row(42)?.textBody)
        assertEquals(1, unread())
        assertEquals(listOf("ringing:$peer", "missed:$peer"), fx.notifier.events)
    }

    /** The timer is counted from the server's receipt, not the caller's clock or our delivery. */
    @Test
    fun ringTimeoutCountsFromWhenTheServerGotTheOffer() {
        deliver(
            CallMessage(offer = CallMessage.Offer(id = 7, type = CallMessage.Offer.Type.OFFER_VIDEO_CALL)),
            serverTs = now - 30_000,
        )

        assertEquals(listOf(SignalCalls.RING_TIMEOUT_MS - 30_000), scheduled.map { it.first })
        runScheduled()
        assertEquals("Missed video call", row(7)?.textBody)
    }

    @Test
    fun hangupBeforeTheTimeoutIsNotCountedTwice() {
        offer(42)
        deliver(CallMessage(hangup = CallMessage.Hangup(id = 42, type = CallMessage.Hangup.Type.HANGUP_NORMAL)))
        assertEquals("Missed voice call", row(42)?.textBody)

        runScheduled()

        assertEquals(1, unread())
        assertEquals(listOf("ringing:$peer", "missed:$peer"), fx.notifier.events)
    }

    @Test
    fun callAnsweredElsewhereStaysAnsweredWhenTheTimerFires() {
        offer(42)
        calls.processCallEventSync(
            SyncMessage.CallEvent(
                callId = 42,
                type = SyncMessage.CallEvent.Type.AUDIO_CALL,
                event = SyncMessage.CallEvent.Event.ACCEPTED,
                conversationId = ServiceId.ACI.parseOrNull(peer)!!.toByteArray().toByteString(),
            ),
        )

        runScheduled()

        assertEquals("Voice call", row(42)?.textBody)
        assertEquals(0, unread())
        assertEquals(listOf("ringing:$peer", "cancelRinging:$peer"), fx.notifier.events)
    }

    @Test
    fun aRedeliveredOfferDoesNotScheduleASecondExpiry() {
        offer(42)
        offer(42)
        calls.expireStaleRinging()

        assertEquals(1, scheduled.size)
    }

    /** After a restart: rows past the timeout are missed at once, younger ones get a timer. */
    @Test
    fun startupSweepExpiresStaleRingingRowsAndSchedulesTheRest() {
        fx.db.upsertConversation(jid = peer, name = "Caller", isGroup = false)
        val old = SignalCalls.rowId(peer, 1)
        val fresh = SignalCalls.rowId(peer, 2)
        fx.db.upsertCallRow(old, peer, peer, (t0 - 10 * 60_000) / 1000, "Incoming voice call", unread = false)
        fx.db.upsertCallRow(fresh, peer, peer, (t0 - 30_000) / 1000, "Incoming video call", unread = false)
        fx.db.upsertCallRow(SignalCalls.rowId(peer, 3), peer, peer, (t0 - 10 * 60_000) / 1000, "Voice call", unread = false)

        calls.expireStaleRinging()

        assertEquals("Missed voice call", fx.db.getMessage(old)?.textBody)
        assertEquals("Incoming video call", fx.db.getMessage(fresh)?.textBody)
        assertEquals(listOf(SignalCalls.RING_TIMEOUT_MS - 30_000), scheduled.map { it.first })
        assertEquals(1, unread())

        runScheduled()

        assertEquals("Missed video call", fx.db.getMessage(fresh)?.textBody)
        assertEquals("Voice call", fx.db.getMessage(SignalCalls.rowId(peer, 3))?.textBody)
        assertEquals(2, unread())
        assertEquals(listOf("missed:$peer", "missed:$peer"), fx.notifier.events)
    }
}
