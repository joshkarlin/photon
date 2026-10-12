package app.photon.signal

// Disappearing messages: the rules for a chat's timer, as Signal-Android
// applies them, and the line a change puts in the thread. Pure, so the
// rules are tested on their own.
//
// Photon follows a chat's timer but never sets it: it never sends an
// expiration-timer update, and every message it sends carries the timer and
// version stored here, so a send can't change a chat's timer. Until Photon
// has learned a chat's timer it doesn't guess: a send goes out at version 0,
// which every Signal client ignores as a timer change.

/**
 * A chat's disappearing-messages timer: [seconds] (0 = off) and Signal's
 * expireTimerVersion. Signal's versions start at 1; 0 means the version
 * isn't known (a timer learned from a client that predates versions), and
 * a timer change at version 0 is ignored by every Signal client
 * (Signal-Android: lower than any stored version; Desktop and iOS drop
 * version 0 outright). Versions only exist for 1:1 chats; a group's timer
 * comes from the group's state and its version is always 1.
 *
 * A chat whose timer Photon hasn't learned has none stored (null).
 */
data class ChatTimer(val seconds: Int, val version: Int) {
    companion object {
        /**
         * What a message carries while its chat's timer isn't known: off,
         * at version 0, so no receiver takes it as a change. The message
         * itself doesn't disappear.
         */
        val UNKNOWN = ChatTimer(0, 0)
    }
}

/** What a message to a chat whose timer is [stored] carries; see [ChatTimer.UNKNOWN]. */
internal fun timerToSend(stored: ChatTimer?): ChatTimer = stored ?: ChatTimer.UNKNOWN

/**
 * What a 1:1 chat's [stored] timer (null: not learned yet) becomes after a
 * message from the other person carrying [seconds] and [version] (null:
 * sent by a client that predates timer versions). Null when it stays as it
 * is.
 *
 * Signal-Android's DataMessageProcessor.handleExpirationUpdate: a message
 * with the same timer changes nothing (not even the version); a different
 * timer applies unless its version is lower than the stored one. A message
 * without a version sets the timer and keeps the stored version. Version 0
 * is never a change, as on every Signal client. The first timer Photon
 * learns for a chat is taken as it is.
 */
internal fun incomingTimerUpdate(stored: ChatTimer?, seconds: Int, version: Int?): ChatTimer? {
    if (version == 0) return null
    if (stored == null) return ChatTimer(seconds, version ?: 0)
    if (seconds == stored.seconds) return null
    if (version != null && version < stored.version) return null
    return ChatTimer(seconds, version ?: stored.version)
}

/**
 * What a 1:1 chat's [stored] timer (null: not learned yet) becomes after a
 * sent transcript (the user's own message from another device) carrying
 * [seconds] and [version]. Null when it stays as it is.
 *
 * Signal-Android's SyncMessageProcessor.handleSynchronizeSentExpirationUpdate:
 * a version at least the stored one applies, even with the same timer (the
 * version still moves up); a lower version is ignored; no version sets the
 * timer and keeps the stored version. Version 0 is never a change.
 */
internal fun syncedTimerUpdate(stored: ChatTimer?, seconds: Int, version: Int?): ChatTimer? {
    if (version == 0) return null
    if (stored == null) return ChatTimer(seconds, version ?: 0)
    val updated = when {
        version == null -> ChatTimer(seconds, stored.version)
        version < stored.version -> return null
        else -> ChatTimer(seconds, version)
    }
    return updated.takeIf { it != stored }
}

/**
 * What a 1:1 chat's [stored] timer (null: not learned yet) becomes after
 * the primary's contact sync gives [seconds] at [version]. Null when it
 * stays as it is.
 *
 * Signal-Android's MultiDeviceContactSyncJob: only a strictly higher
 * version applies, since a contact sync is a snapshot that may be older
 * than what a message said. Here a timer Photon hasn't learned takes it
 * too.
 */
internal fun contactSyncTimerUpdate(stored: ChatTimer?, seconds: Int, version: Int): ChatTimer? = when {
    version <= 0 -> null
    stored == null -> ChatTimer(seconds, version)
    version > stored.version -> ChatTimer(seconds, version)
    else -> null
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
