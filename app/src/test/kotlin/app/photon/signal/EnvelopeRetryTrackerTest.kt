package app.photon.signal

import okio.ByteString.Companion.toByteString
import org.whispersystems.signalservice.internal.push.Envelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvelopeRetryTrackerTest {

    private val tracker = EnvelopeRetryTracker<String>(maxFailures = 3, maxEntries = 2)

    /** Left un-acked twice, then a poison envelope is given up (acked) on the third failure. */
    @Test
    fun givesUpAfterThreeFailures() {
        assertFalse(tracker.recordFailure("guid", "payload"))
        assertFalse(tracker.recordFailure("guid", null))
        assertTrue(tracker.recordFailure("guid", null))
        // Given up: forgotten, so a fresh envelope with the same key starts over.
        assertNull(tracker.pending("guid"))
        assertFalse(tracker.recordFailure("guid", null))
    }

    /** The decrypted payload survives for the redelivery, which can't be decrypted again. */
    @Test
    fun holdsDecryptedPayloadUntilProcessed() {
        tracker.recordFailure("guid", "decrypted")
        tracker.recordFailure("guid", null)

        assertEquals("decrypted", tracker.pending("guid"))
        assertTrue(tracker.isHeld("guid"))
        tracker.clear("guid")
        assertNull(tracker.pending("guid"))
        assertFalse(tracker.isHeld("guid"))
    }

    @Test
    fun isBounded() {
        tracker.recordFailure("a", "1")
        tracker.recordFailure("b", "2")
        tracker.recordFailure("c", "3")

        assertNull(tracker.pending("a"))
        assertEquals("3", tracker.pending("c"))
    }

    @Test
    fun keyPrefersServerGuid() {
        assertEquals("guid-1", envelopeRetryKey(Envelope(serverGuid = "guid-1", clientTimestamp = 5)))
        assertEquals(
            "0102",
            envelopeRetryKey(Envelope(serverGuidBinary = byteArrayOf(1, 2).toByteString(), clientTimestamp = 5)),
        )
        assertEquals(
            "aci:2:5:9",
            envelopeRetryKey(Envelope(sourceServiceId = "aci", sourceDeviceId = 2, clientTimestamp = 5, serverTimestamp = 9)),
        )
    }
}
