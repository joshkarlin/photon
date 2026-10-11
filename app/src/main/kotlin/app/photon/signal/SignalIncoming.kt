package app.photon.signal

import okio.ByteString
import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.internal.push.DataMessage

/**
 * A service id carried as both a string and a binary field. Current clients
 * are moving to the binary form, so a missing string must not drop the
 * reference. Binary wins, as in the library's own ServiceId.parseOrNull(String,
 * ByteString). Returns the canonical string form ("uuid" for an ACI); an
 * unparseable string is passed through so the caller's lookup still runs.
 */
internal fun serviceIdString(string: String?, binary: ByteString?): String? {
    val fromBinary = binary?.takeIf { it.size > 0 }?.let { ServiceId.parseOrNull(it.toByteArray()) }
    return fromBinary?.toString()
        ?: string?.takeIf { it.isNotBlank() }?.let { ServiceId.parseOrNull(it)?.toString() ?: it }
}

/** An incoming reaction (or reaction removal) keyed by its target's "{author}_{ts}" prefix. */
internal sealed interface IncomingReaction {
    val targetPrefix: String

    data class Set(override val targetPrefix: String, val emoji: String) : IncomingReaction
    data class Remove(override val targetPrefix: String) : IncomingReaction
}

/**
 * Interpret a DataMessage.Reaction. A reaction with `remove` set withdraws
 * the sender's reaction; storing it as a reaction showed the emoji the user
 * had just taken back.
 */
internal fun incomingReactionFor(reaction: DataMessage.Reaction, fallbackAuthor: String): IncomingReaction? {
    val targetTs = reaction.targetSentTimestamp ?: return null
    val author = serviceIdString(reaction.targetAuthorAci, reaction.targetAuthorAciBinary) ?: fallbackAuthor
    val prefix = "${author}_$targetTs"
    if (reaction.remove == true) return IncomingReaction.Remove(prefix)
    val emoji = reaction.emoji?.takeIf { it.isNotEmpty() } ?: return null
    return IncomingReaction.Set(prefix, emoji)
}
