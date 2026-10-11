package app.photon.chat

import androidx.compose.runtime.Composable
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.Platform
import app.photon.data.names.DisplayNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * One messaging platform as the chat screens use it. Each session wraps its
 * repository in one ([WhatsAppBackend], [SignalBackend], [SmsBackend]), so
 * one chat screen and one chat list serve all three.
 *
 * Errors: every suspend call returns, or throws [ChatBackendException] and
 * nothing else (bar cancellation). A platform's error payloads and its own
 * exceptions become that. A send that fails also leaves the message failed
 * in its thread, for the user to retry.
 */
interface ChatBackend {
    val platform: Platform
    val capabilities: ChatCapabilities

    /** Every chat to list, names resolved, newest first; null until first read. */
    val conversations: StateFlow<List<Conversation>?>

    /** One chat, read now: for a screen opened before [conversations] has it. */
    fun conversation(jid: String): Conversation?

    /** A chat's newest messages, newest first, read again whenever they change. */
    fun messages(jid: String): Flow<List<Message>>

    /** A group's sender names by JID, resolved again as names are learned. */
    fun participantNames(jid: String): Flow<Map<String, String>>

    suspend fun sendText(jid: String, text: String, replyToId: String?)

    /**
     * Send the staged file [path]. [fileName] is the name the user's file
     * had (the staged copy's is Photon's own): a document is sent and shown
     * under it.
     */
    suspend fun sendMedia(jid: String, path: String, mime: String, replyToId: String?, fileName: String? = null)

    /** A voice note recorded in Photon. */
    suspend fun sendAudio(jid: String, path: String, replyToId: String?) =
        sendMedia(jid, path, VOICE_NOTE_MIME, replyToId)

    suspend fun react(jid: String, messageId: String, senderJid: String, emoji: String)

    /** Send a failed message of the user's again. */
    suspend fun retry(messageId: String)

    suspend fun delete(jid: String, messageId: String, forEveryone: Boolean)

    /**
     * Mark [jid] read through its newest incoming messages, [incomingIds]
     * (newest first). Throws when the read didn't fully reach the platform,
     * so the caller can ask again; see [MarkReadGate].
     */
    suspend fun markRead(jid: String, incomingIds: List<String>)

    /** Download a message's media and return its local path. */
    suspend fun downloadMedia(messageId: String): String

    /**
     * Whether [phoneE164] ("+447700900123") can be messaged here, for New
     * message: the chat to open, new or existing, or why it can't be. Never
     * throws (bar cancellation); not being able to find out is
     * [Reach.Unreachable] with the reason.
     */
    suspend fun reach(phoneE164: String): Reach

    // ─── Platform hooks ─────────────────────────────────────────────

    /** Emits when a request that failed is worth trying again, e.g. the platform reconnected. */
    val reconnects: Flow<Unit> get() = emptyFlow()

    /** Runs once each time a chat opens. */
    suspend fun onOpen(jid: String, isGroup: Boolean) {}

    /** Shown under the chat's top bar. */
    @Composable
    fun Header(jid: String, isGroup: Boolean, title: String, participantNames: Map<String, String>) {}

    /**
     * A DM's title while it has no conversation (a chat just started from
     * New message, until its first message): the person by [DisplayNames]'
     * policy, from what this platform knows of [jid]. Called as the chat opens.
     */
    fun newChatTitle(jid: String): String = DisplayNames.title(jid, null, isGroup = false, platform)

    /** The number a DM's contact can be saved under, if the chat has one. Called when its title is tapped. */
    fun contactPhone(jid: String): String?

    /**
     * False when [jid] can't have a number to save, so its title isn't
     * tappable at all. Checked as the chat composes, so it must be cheap.
     */
    fun mayHaveContactPhone(jid: String): Boolean = true

    companion object {
        /** How Photon records voice notes. */
        const val VOICE_NOTE_MIME = "audio/ogg"
    }
}

/** What a platform can do, for the chat screen to offer. */
data class ChatCapabilities(
    val reply: Boolean,
    val media: Boolean,
    val audio: Boolean,
    val reactions: Boolean,
    val delete: Boolean,
    val deleteForEveryone: Boolean,
    val contactEditing: Boolean,
)

/** Whether a person can be messaged on a platform ([ChatBackend.reach]). */
sealed interface Reach {
    /** Open [jid]: [existing] when it's a chat with messages, else a new one. */
    data class Reachable(val jid: String, val existing: Boolean) : Reach

    /** Not here, and [reason], fit to show ("not on WhatsApp", "WhatsApp disconnected"). */
    data class Unreachable(val reason: String) : Reach
}

/** A [ChatBackend] call that didn't happen. [message] is fit to show the user. */
class ChatBackendException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * [block], under the [ChatBackend] error contract: anything it throws other
 * than cancellation becomes a [ChatBackendException] with the same message.
 */
internal suspend fun <T> backendCall(what: String, block: suspend () -> T): T = try {
    block()
} catch (e: ChatBackendException) {
    throw e
} catch (e: TimeoutCancellationException) {
    // A request that timed out failed; nothing cancelled the caller.
    throw ChatBackendException(e.message ?: "$what timed out", e)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw ChatBackendException(e.message ?: "$what failed", e)
}
