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

    // ── The user's own messages from other devices, and contact sync (handleSynchronizeSentExpirationUpdate) ──

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
