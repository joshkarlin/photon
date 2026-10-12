package app.photon.signal

// Disappearing messages: the rules for a chat's timer, as Signal-Android
// applies them, and the line a change puts in the thread. Pure, so the
// rules are tested on their own.
//
// Photon follows a chat's timer but never sets it: it never sends an
// expiration-timer update, and every message it sends carries the timer and
// version stored here, so a send can't turn a chat's timer off.

/**
 * A chat's disappearing-messages timer: [seconds] (0 = off) and Signal's
 * expireTimerVersion. Versions only exist for 1:1 chats; a group's timer
 * comes from the group's state and its version is always 1.
 */
data class ChatTimer(val seconds: Int, val version: Int) {
    companion object {
        /** A chat Photon has seen no timer for: off, at Signal's starting version. */
        val DEFAULT = ChatTimer(0, 1)
    }
}

/**
 * What a 1:1 chat's [stored] timer becomes after a message from the other
 * person carrying [seconds] and [version] (null: sent by a client that
 * predates timer versions). Null when it stays as it is.
 *
 * Signal-Android's DataMessageProcessor.handleExpirationUpdate: a message
 * with the same timer changes nothing (not even the version); a different
 * timer applies unless its version is lower than the stored one. A message
 * without a version sets the timer and keeps the stored version.
 */
internal fun incomingTimerUpdate(stored: ChatTimer, seconds: Int, version: Int?): ChatTimer? {
    if (seconds == stored.seconds) return null
    if (version != null && version < stored.version) return null
    return ChatTimer(seconds, version ?: stored.version)
}

/**
 * What a 1:1 chat's [stored] timer becomes after a sent transcript (the
 * user's own message from another device) or a contact sync carrying
 * [seconds] and [version]. Null when it stays as it is.
 *
 * Signal-Android's SyncMessageProcessor.handleSynchronizeSentExpirationUpdate:
 * a version at least the stored one applies, even with the same timer (the
 * version still moves up); a lower version is ignored; no version sets the
 * timer and keeps the stored version.
 */
internal fun syncedTimerUpdate(stored: ChatTimer, seconds: Int, version: Int?): ChatTimer? {
    val updated = when {
        version == null -> ChatTimer(seconds, stored.version)
        version < stored.version -> return null
        else -> ChatTimer(seconds, version)
    }
    return updated.takeIf { it != stored }
}

/** The centred line a timer change puts in the thread: "Disappearing messages · 1 week". */
fun disappearingMessagesLine(seconds: Int): String =
    if (seconds <= 0) "Disappearing messages off" else "Disappearing messages · ${formatTimerDuration(seconds)}"

private val TIMER_UNITS = listOf(
    7 * 86_400 to "week",
    86_400 to "day",
    3_600 to "hour",
    60 to "minute",
    1 to "second",
)

/**
 * A timer as words: "1 week", "4 weeks", "8 hours", "30 seconds". A custom
 * timer that isn't a whole unit lists each part, largest first:
 * "1 day 12 hours". As Signal-Android's ExpirationUtil.
 */
fun formatTimerDuration(seconds: Int): String {
    var left = seconds.coerceAtLeast(0)
    val parts = buildList {
        for ((size, unit) in TIMER_UNITS) {
            val count = left / size
            if (count > 0) add(if (count == 1) "1 $unit" else "$count ${unit}s")
            left %= size
        }
    }
    return parts.joinToString(" ").ifEmpty { "0 seconds" }
}

/** Longest the expiry sweep sleeps: it also catches clocks started while it slept. */
internal const val MAX_EXPIRY_WAIT_MS = 5 * 60_000L

/**
 * How long the expiry sweep waits before running again: until [nextExpiryAt]
 * (epoch ms; null when nothing is counting down), at least a second and at
 * most [MAX_EXPIRY_WAIT_MS].
 */
internal fun expiryWaitMs(nextExpiryAt: Long?, nowMs: Long): Long =
    ((nextExpiryAt ?: Long.MAX_VALUE) - nowMs).coerceIn(1_000L, MAX_EXPIRY_WAIT_MS)
