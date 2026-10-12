package app.photon.data.model

import org.junit.Test
import kotlin.test.assertEquals

/** One wording for a disappearing-messages timer: its largest whole unit. */
class TimerDurationTest {

    @Test
    fun timersReadInTheirLargestWholeUnit() {
        val expected = mapOf(
            1L to "1 second",
            30L to "30 seconds",
            90L to "90 seconds",
            300L to "5 minutes",
            3600L to "1 hour",
            8 * 3600L to "8 hours",
            36 * 3600L to "36 hours",
            24 * 3600L to "1 day",
            3 * 24 * 3600L to "3 days",
            7 * 24 * 3600L to "1 week",
            28 * 24 * 3600L to "4 weeks",
            90 * 24 * 3600L to "90 days",
        )
        expected.forEach { (seconds, text) -> assertEquals(text, formatTimerDuration(seconds), "$seconds s") }
    }
}
