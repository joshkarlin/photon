package app.photon.data.model

/**
 * A disappearing-messages timer in its largest whole unit, as WhatsApp and
 * Signal offer them: "30 seconds", "5 minutes", "8 hours", "1 day",
 * "1 week", "4 weeks", "90 days". A timer that isn't a whole number of a
 * larger unit stays in the smaller one ("90 seconds", "36 hours").
 *
 * The one wording for a timer everywhere: the chat header, and the line a
 * timer change adds to a thread on either platform (the WhatsApp bridge's
 * durationText follows the same rule).
 */
fun formatTimerDuration(seconds: Long): String {
    val (count, unit) = TIMER_UNITS.firstOrNull { (size, _) -> seconds >= size && seconds % size == 0L }
        ?.let { (size, name) -> seconds / size to name }
        ?: (seconds to "second")
    return if (count == 1L) "1 $unit" else "$count ${unit}s"
}

private val TIMER_UNITS = listOf(
    7 * 24 * 3600L to "week",
    24 * 3600L to "day",
    3600L to "hour",
    60L to "minute",
)
