package app.photon.ui.shared

import com.thelightphone.sdk.ui.LightTopBarCenter

/**
 * The thread's top-bar title. While the chat's messages disappear, the
 * SDK's two-line centre puts the timer under the name in Detail
 * ("disappearing · 1 week"), as LightOS writes a state: lower case.
 */
internal fun chatTitle(title: String, disappearingSeconds: Long, onClick: (() -> Unit)?): LightTopBarCenter =
    if (disappearingSeconds > 0) {
        LightTopBarCenter.TwoLineDetail(title, disappearingLine(disappearingSeconds), onClick = onClick)
    } else {
        LightTopBarCenter.Text(title, onClick = onClick)
    }

/** The line under a chat's title while its messages disappear. */
internal fun disappearingLine(seconds: Long): String = "disappearing · ${formatTimerDuration(seconds)}"

/**
 * A disappearing-messages timer in its largest whole unit, as WhatsApp and
 * Signal offer them: "30 seconds", "5 minutes", "8 hours", "1 day",
 * "1 week", "4 weeks", "90 days". A timer that isn't a whole number of a
 * larger unit stays in the smaller one ("90 seconds", "36 hours").
 */
internal fun formatTimerDuration(seconds: Long): String {
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
