package app.photon.signal

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A chat's timer follows Signal-Android's version rules; the line names the timer in words. */
class DisappearingMessagesTest {

    private val week = 7 * 86_400
    private val day = 86_400

    // ── Messages from the other person (DataMessageProcessor.handleExpirationUpdate) ──

    @Test
    fun aDifferentTimerAtAHigherVersionApplies() {
        assertEquals(ChatTimer(week, 3), incomingTimerUpdate(ChatTimer(0, 2), week, 3))
    }

    @Test
    fun aDifferentTimerAtTheSameVersionApplies() {
        assertEquals(ChatTimer(0, 2), incomingTimerUpdate(ChatTimer(week, 2), 0, 2))
    }

    @Test
    fun aLowerVersionIsIgnored() {
        assertNull(incomingTimerUpdate(ChatTimer(week, 3), 0, 2))
    }

    /** The same timer changes nothing, not even a higher version. */
    @Test
    fun theSameTimerChangesNothing() {
        assertNull(incomingTimerUpdate(ChatTimer(week, 2), week, 5))
        assertNull(incomingTimerUpdate(ChatTimer(week, 2), week, null))
    }

    /** A client that predates versions sets the timer and leaves the version. */
    @Test
    fun noVersionSetsTheTimerAndKeepsTheVersion() {
        assertEquals(ChatTimer(day, 4), incomingTimerUpdate(ChatTimer(week, 4), day, null))
    }

    /**
     * The roadmap's case: a timer set by an older client is at version 1, so
     * a message with no timer at version 1 (what the library's builder sends
     * unless told otherwise) turns it off. This is why every send carries the
     * stored timer and version.
     */
    @Test
    fun atVersionOneAMessageWithoutATimerTurnsItOff() {
        assertEquals(ChatTimer(0, 1), incomingTimerUpdate(ChatTimer(week, 1), 0, 1))
        assertNull(incomingTimerUpdate(ChatTimer(week, 1), week, 1))
    }

    // ── A timer Photon hasn't learned ──

    /** The first timer learned is taken as it is, version and all (0 when the client sent none). */
    @Test
    fun anUnknownTimerTakesTheFirstOneLearned() {
        assertEquals(ChatTimer(week, 3), incomingTimerUpdate(null, week, 3))
        assertEquals(ChatTimer(0, 2), incomingTimerUpdate(null, 0, 2))
        assertEquals(ChatTimer(day, 0), incomingTimerUpdate(null, day, null))
        assertEquals(ChatTimer(week, 4), syncedTimerUpdate(null, week, 4))
        assertEquals(ChatTimer(day, 0), syncedTimerUpdate(null, day, null))
    }

    /** Version 0 is never a change, as on every Signal client: it's what Photon sends while it doesn't know. */
    @Test
    fun versionZeroIsNeverAChange() {
        assertNull(incomingTimerUpdate(null, 0, 0))
        assertNull(incomingTimerUpdate(ChatTimer(week, 2), 0, 0))
        assertNull(syncedTimerUpdate(null, 0, 0))
        assertNull(syncedTimerUpdate(ChatTimer(week, 2), 0, 0))
        assertNull(contactSyncTimerUpdate(null, 0, 0))
    }

    /** While unknown, a send is off at version 0; once known, it's the stored timer at its version. */
    @Test
    fun aSendCarriesTheStoredTimerOrVersionZero() {
        assertEquals(ChatTimer(0, 0), timerToSend(null))
        assertEquals(ChatTimer.UNKNOWN, timerToSend(null))
        assertEquals(ChatTimer(week, 1), timerToSend(ChatTimer(week, 1)))
        // Learned from a client without versions: the timer, at version 0.
        assertEquals(ChatTimer(day, 0), timerToSend(ChatTimer(day, 0)))
    }

    /**
     * Every receiver ignores version 0, whatever it has stored: Signal-Android's
     * versions start at 1 and a lower one is ignored, so Photon's unknown send
     * can't turn a version-1 timer off.
     */
    @Test
    fun anUnknownSendCantTurnAVersionOneTimerOff() {
        val unknown = timerToSend(null)
        assertNull(incomingTimerUpdate(ChatTimer(week, 1), unknown.seconds, unknown.version))
        assertNull(syncedTimerUpdate(ChatTimer(week, 1), unknown.seconds, unknown.version))
    }

    // ── The primary's contact sync (MultiDeviceContactSyncJob) ──

    @Test
    fun aContactSyncNeedsAStrictlyHigherVersion() {
        assertEquals(ChatTimer(week, 3), contactSyncTimerUpdate(ChatTimer(0, 2), week, 3))
        // A snapshot at the same version may be older than what a message said.
        assertNull(contactSyncTimerUpdate(ChatTimer(0, 2), week, 2))
        assertNull(contactSyncTimerUpdate(ChatTimer(0, 2), week, 1))
        assertEquals(ChatTimer(week, 1), contactSyncTimerUpdate(null, week, 1))
        // A timer learned without a version (0) takes any real one.
        assertEquals(ChatTimer(0, 1), contactSyncTimerUpdate(ChatTimer(day, 0), 0, 1))
    }

    // ── The user's own messages from other devices (handleSynchronizeSentExpirationUpdate) ──

    @Test
    fun aTranscriptAtTheSameOrAHigherVersionApplies() {
        assertEquals(ChatTimer(week, 2), syncedTimerUpdate(ChatTimer(0, 2), week, 2))
        assertEquals(ChatTimer(day, 3), syncedTimerUpdate(ChatTimer(week, 2), day, 3))
    }

    /** Unlike an incoming message, a transcript moves the version up even with the same timer. */
    @Test
    fun aTranscriptMovesTheVersionUpWithTheSameTimer() {
        assertEquals(ChatTimer(week, 4), syncedTimerUpdate(ChatTimer(week, 2), week, 4))
    }

    @Test
    fun aTranscriptAtALowerVersionIsIgnored() {
        assertNull(syncedTimerUpdate(ChatTimer(week, 3), 0, 2))
    }

    @Test
    fun aTranscriptWithoutAVersionSetsOnlyTheTimer() {
        assertEquals(ChatTimer(day, 3), syncedTimerUpdate(ChatTimer(week, 3), day, null))
        assertNull(syncedTimerUpdate(ChatTimer(week, 3), week, null))
    }

    @Test
    fun anUnchangedTranscriptChangesNothing() {
        assertNull(syncedTimerUpdate(ChatTimer(week, 3), week, 3))
    }

    // ── The line ──

    @Test
    fun theLineNamesTheTimer() {
        assertEquals("Disappearing messages · 1 week", disappearingMessagesLine(week))
        assertEquals("Disappearing messages off", disappearingMessagesLine(0))
    }

    @Test
    fun durationsUseTheLargestWholeUnits() {
        assertEquals("30 seconds", formatTimerDuration(30))
        assertEquals("1 second", formatTimerDuration(1))
        assertEquals("5 minutes", formatTimerDuration(300))
        assertEquals("1 hour", formatTimerDuration(3_600))
        assertEquals("8 hours", formatTimerDuration(8 * 3_600))
        assertEquals("1 day", formatTimerDuration(day))
        assertEquals("1 week", formatTimerDuration(week))
        assertEquals("4 weeks", formatTimerDuration(4 * week))
    }

    /** A custom timer lists each part, largest first. */
    @Test
    fun customTimersListEachPart() {
        assertEquals("1 day 12 hours", formatTimerDuration(day + 12 * 3_600))
        assertEquals("1 minute 30 seconds", formatTimerDuration(90))
        assertEquals("1 week 1 day 1 hour 1 minute 1 second", formatTimerDuration(week + day + 3_600 + 61))
        assertEquals("0 seconds", formatTimerDuration(0))
    }

    // ── The sweep's wait ──

    @Test
    fun theSweepWaitsUntilTheNextExpiryWithinBounds() {
        val now = 1_700_000_000_000
        assertEquals(30_000, expiryWaitMs(now + 30_000, now))
        assertEquals(1_000, expiryWaitMs(now - 5_000, now))
        assertEquals(MAX_EXPIRY_WAIT_MS, expiryWaitMs(now + 3_600_000, now))
        assertEquals(MAX_EXPIRY_WAIT_MS, expiryWaitMs(null, now))
    }
}
