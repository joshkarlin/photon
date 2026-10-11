package app.photon.ui.shared

import org.junit.Test
import java.util.Calendar
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FormatTimestampTest {

    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 11, 15, 30, 0) }

    private fun secondsAt(adjust: Calendar.() -> Unit): Long =
        (now.clone() as Calendar).apply(adjust).timeInMillis / 1000

    @Test
    fun todayShowsTheTime() {
        assertEquals("09:05", formatTimestamp(secondsAt { set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 5) }, now.timeInMillis))
    }

    @Test
    fun sameDayOfAnotherMonthIsNotToday() {
        val lastMonth = formatTimestamp(secondsAt { add(Calendar.MONTH, -1) }, now.timeInMillis)
        assertEquals("11/09", lastMonth)
    }

    @Test
    fun yesterdayAcrossAMonthBoundary() {
        val first = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 1, 8, 0, 0) }
        val lastOfOctober = (first.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }
        assertEquals("Yesterday", formatTimestamp(lastOfOctober.timeInMillis / 1000, first.timeInMillis))
    }

    @Test
    fun emptyForMissingTimestamp() {
        assertEquals("", formatTimestamp(0L, now.timeInMillis))
        assertNotEquals("", formatTimestamp(secondsAt { add(Calendar.DAY_OF_MONTH, -20) }, now.timeInMillis))
    }
}
