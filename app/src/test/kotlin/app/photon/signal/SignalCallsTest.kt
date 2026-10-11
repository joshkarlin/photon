package app.photon.signal

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.CallMessage.Hangup
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SignalCallsTest {

    private val local = 3
    private val primary = 1

    @Test
    fun everyCallTextRoundTrips() {
        for (outcome in CallOutcome.entries) {
            for (video in listOf(false, true)) {
                val state = CallState(outcome, video)
                assertEquals(state, SignalCalls.parse(SignalCalls.text(state)))
            }
        }
        assertEquals("Incoming voice call", SignalCalls.text(CallState(CallOutcome.RINGING, false)))
        assertEquals("Missed video call", SignalCalls.text(CallState(CallOutcome.MISSED, true)))
        assertEquals("Voice call", SignalCalls.text(CallState(CallOutcome.ANSWERED, false)))
        assertEquals("Declined voice call", SignalCalls.text(CallState(CallOutcome.DECLINED, false)))
        assertNull(SignalCalls.parse("hello"))
    }

    @Test
    fun ringingCallEndsAsMissedWhenTheCallerHangsUp() {
        val ringing = SignalCalls.next(null, CallSignal.OFFER)
        assertEquals(CallOutcome.RINGING, ringing)
        val hangup = SignalCalls.signalForHangup(Hangup.Type.HANGUP_NORMAL, null, local)
        assertEquals(CallOutcome.MISSED, SignalCalls.next(ringing, hangup!!))
        assertTrue(SignalCalls.isUnread(CallOutcome.MISSED))
    }

    @Test
    fun answeredOrDeclinedOnAnotherDeviceStopsRingingWithoutCountingUnread() {
        val accepted = SignalCalls.signalForHangup(Hangup.Type.HANGUP_ACCEPTED, primary, local)!!
        val declined = SignalCalls.signalForHangup(Hangup.Type.HANGUP_DECLINED, primary, local)!!

        assertEquals(CallOutcome.ANSWERED, SignalCalls.next(CallOutcome.RINGING, accepted))
        assertEquals(CallOutcome.DECLINED, SignalCalls.next(CallOutcome.RINGING, declined))
        assertFalse(SignalCalls.isUnread(CallOutcome.ANSWERED))
        assertFalse(SignalCalls.isUnread(CallOutcome.DECLINED))
    }

    @Test
    fun busyOrNeedPermissionOnAnotherDeviceIsMissed() {
        assertEquals(CallSignal.ENDED_UNANSWERED, SignalCalls.signalForHangup(Hangup.Type.HANGUP_BUSY, primary, local))
        assertEquals(
            CallSignal.ENDED_UNANSWERED,
            SignalCalls.signalForHangup(Hangup.Type.HANGUP_NEED_PERMISSION, primary, local),
        )
    }

    /** Photon never answers, so a hangup naming its own device id is noise. */
    @Test
    fun hangupNamingThisDeviceIsIgnored() {
        assertNull(SignalCalls.signalForHangup(Hangup.Type.HANGUP_ACCEPTED, local, local))
        assertNull(SignalCalls.signalForHangup(Hangup.Type.HANGUP_DECLINED, local, local))
        assertNull(SignalCalls.signalForHangup(Hangup.Type.HANGUP_ACCEPTED, null, local))
    }

    @Test
    fun transitionsNeverRegress() {
        // Redelivered offer doesn't re-ring; signals without a row create nothing.
        assertNull(SignalCalls.next(CallOutcome.RINGING, CallSignal.OFFER))
        assertNull(SignalCalls.next(null, CallSignal.ENDED_UNANSWERED))
        assertNull(SignalCalls.next(null, CallSignal.ANSWERED_ELSEWHERE))
        // A late hangup can't turn an answered/declined call into a missed one.
        assertNull(SignalCalls.next(CallOutcome.ANSWERED, CallSignal.ENDED_UNANSWERED))
        assertNull(SignalCalls.next(CallOutcome.DECLINED, CallSignal.ENDED_UNANSWERED))
        // A missed call isn't relabelled declined, but can turn out answered.
        assertNull(SignalCalls.next(CallOutcome.MISSED, CallSignal.DECLINED_ELSEWHERE))
        assertEquals(CallOutcome.ANSWERED, SignalCalls.next(CallOutcome.MISSED, CallSignal.ANSWERED_ELSEWHERE))
        // A stale offer goes straight to missed.
        assertEquals(CallOutcome.MISSED, SignalCalls.next(null, CallSignal.STALE_OFFER))
    }

    /** No hangup reached this device in time: a ringing call becomes missed, nothing else moves. */
    @Test
    fun ringTimeoutOnlyEndsARingingCall() {
        assertEquals(CallOutcome.MISSED, SignalCalls.next(CallOutcome.RINGING, CallSignal.RING_EXPIRED))
        assertNull(SignalCalls.next(null, CallSignal.RING_EXPIRED))
        assertNull(SignalCalls.next(CallOutcome.MISSED, CallSignal.RING_EXPIRED))
        assertNull(SignalCalls.next(CallOutcome.ANSWERED, CallSignal.RING_EXPIRED))
        assertNull(SignalCalls.next(CallOutcome.DECLINED, CallSignal.RING_EXPIRED))
        assertEquals(listOf("Incoming voice call", "Incoming video call"), SignalCalls.RINGING_TEXTS)
    }

    @Test
    fun offerIsStaleOnlyWhenHeldLongerThanRingRtcExpiry() {
        val received = 1_700_000_000_000L
        assertFalse(SignalCalls.isStaleOffer(received, received + 5_000, received + 5_000))
        assertTrue(SignalCalls.isStaleOffer(received, received + 121_000, received + 121_000))
        // No delivery header: compare with now.
        assertTrue(SignalCalls.isStaleOffer(received, 0, received + 600_000))
        // No server timestamp: can't tell, so ring.
        assertFalse(SignalCalls.isStaleOffer(null, received, received))
    }

    @Test
    fun rowIdsDontCollideWithMessageKeys() {
        val aci = "00000000-0000-4000-8000-000000000001"
        val id = SignalCalls.rowId(aci, 42)
        assertEquals("call_${aci}_42", id)
        // Can't be mistaken for an (author, timestamp) message key.
        assertNull(MessageKeys.parse(id))
    }

    @Test
    fun conversationIdMapsAciAndGroupIds() {
        val aci = "00000000-0000-4000-8000-000000000001"
        val aciBytes = org.signal.core.models.ServiceId.ACI.parseOrNull(aci)!!.toByteArray()
        assertEquals(aci, SignalCalls.conversationJidFor(aciBytes))
        assertNull(SignalCalls.conversationJidFor(ByteArray(0)))
        assertNull(SignalCalls.conversationJidFor(ByteArray(7)))
    }

    /**
     * Group conversation ids must encode exactly like
     * SignalGroupV2Manager.jidFromMasterKey (android.util.Base64 with
     * NO_WRAP | URL_SAFE), or a call-log sync can't find the group chat.
     */
    @Test
    fun groupConversationIdEncodesLikeGroupJids() {
        val random = Random(7)
        repeat(50) {
            val groupId = random.nextBytes(32)
            assertEquals(
                android.util.Base64.encodeToString(groupId, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE),
                SignalCalls.conversationJidFor(groupId),
            )
        }
    }
}
