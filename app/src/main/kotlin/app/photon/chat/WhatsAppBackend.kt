package app.photon.chat

import app.photon.data.model.BridgeEvent
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.Platform
import app.photon.data.model.string
import app.photon.data.names.DisplayNames
import app.photon.data.repository.ChatRepository
import app.photon.service.BridgeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * WhatsApp through its bridge. The bridge answers a failed request with an
 * error in its response rather than failing it; those become
 * [ChatBackendException]s here.
 */
class WhatsAppBackend(
    private val repository: ChatRepository,
    private val bridge: BridgeClient,
    scope: CoroutineScope,
) : ChatBackend {

    override val platform = Platform.WHATSAPP

    override val capabilities = ChatCapabilities(
        reply = true, media = true, audio = true, reactions = true,
        delete = true, deleteForEveryone = true, contactEditing = true,
    )

    // Status broadcasts, the server's metadata chat and broadcast lists
    // aren't chats, and a chat with no activity has nothing to show.
    override val conversations: StateFlow<List<Conversation>?> = repository.conversations
        .map { list -> list?.filter { !it.isPseudoChat && it.lastTimestamp > 0 } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    override fun conversation(jid: String): Conversation? = repository.getConversation(jid)

    override fun messages(jid: String): Flow<List<Message>> = repository.messages(jid)

    override fun participantNames(jid: String): Flow<Map<String, String>> = repository.participantNames(jid)

    override suspend fun sendText(jid: String, text: String, replyToId: String?) {
        request("Send") { repository.sendMessage(jid, text, replyToId) }
    }

    override suspend fun sendMedia(jid: String, path: String, mime: String, replyToId: String?) {
        request("Send") { repository.sendMedia(jid, path, mime, null, replyToId) }
    }

    override suspend fun react(jid: String, messageId: String, senderJid: String, emoji: String) {
        request("React") { repository.sendReaction(jid, messageId, senderJid, emoji) }
    }

    override suspend fun retry(messageId: String) {
        request("Retry") { repository.retryMessage(messageId) }
    }

    override suspend fun delete(jid: String, messageId: String, forEveryone: Boolean) {
        request("Delete") { repository.deleteMessage(jid, messageId, forEveryone) }
    }

    // The bridge clears the chat's unread state whatever happens, and
    // reports an error when the receipt to WhatsApp didn't go out.
    override suspend fun markRead(jid: String, incomingIds: List<String>) {
        request("Mark read") { repository.markRead(jid, incomingIds) }
    }

    override suspend fun downloadMedia(messageId: String): String =
        request("Download") { bridge.downloadMedia(messageId) }.string("local_path")
            ?: throw ChatBackendException("Download not available")

    // A chat already listed under the number opens even offline. Otherwise
    // the bridge asks WhatsApp (check_on_whatsapp), which needs it
    // connected: offline, whether they're on WhatsApp is unknown, not "no".
    override suspend fun reach(phoneE164: String): Reach {
        val digits = phoneE164.filter(Char::isDigit)
        val phoneJid = "$digits@s.whatsapp.net"
        val listed = withContext(Dispatchers.IO) { runCatching { repository.storedConversation(phoneJid) }.getOrNull() }
        if (listed != null && listed.lastTimestamp > 0) return Reach.Reachable(phoneJid, existing = true)
        when {
            bridge.whatsappState.value == "logged_out" -> return Reach.Unreachable(NOT_LINKED)
            !bridge.isConnected.value || bridge.whatsappState.value != "connected" -> return Reach.Unreachable(DISCONNECTED)
        }
        val response = try {
            withTimeout(REACH_TIMEOUT_MS) { bridge.checkOnWhatsApp(listOf(phoneE164)) }
        } catch (_: TimeoutCancellationException) {
            return Reach.Unreachable(CHECK_FAILED)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Reach.Unreachable(CHECK_FAILED)
        }
        response.string("error")?.let { return Reach.Unreachable(if (it == DISCONNECTED) DISCONNECTED else CHECK_FAILED) }
        val result = (response.payload["results"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return Reach.Unreachable(CHECK_FAILED)
        val jid = result["jid"]?.jsonPrimitive?.contentOrNull
        if (result["on_whatsapp"]?.jsonPrimitive?.booleanOrNull != true || jid.isNullOrBlank()) {
            return Reach.Unreachable(NOT_ON_WHATSAPP)
        }
        return Reach.Reachable(jid, existing = result["existing"]?.jsonPrimitive?.booleanOrNull == true)
    }

    // A mark-read whose receipt failed goes out again once WhatsApp is back.
    override val reconnects: Flow<Unit> = bridge.whatsappState.filter { it == "connected" }.map { }

    // On opening a group, backfill any missing sender names from the contact
    // store: historical senders history sync couldn't name. The bridge then
    // broadcasts conversation_updated, which refreshes participantNames.
    override suspend fun onOpen(jid: String, isGroup: Boolean) {
        if (isGroup) request("Name participants") { repository.resolveParticipants(jid) }
    }

    // The LP3 contact for a phone JID's number, else the number.
    override fun newChatTitle(jid: String): String = repository.names.person(jid, bridgeName = null)

    // Phone JIDs carry the number; an @lid DM has none to save.
    override fun contactPhone(jid: String): String? = DisplayNames.phoneOf(jid, Platform.WHATSAPP)

    private suspend fun request(what: String, call: suspend () -> BridgeEvent): BridgeEvent {
        val response = backendCall(what) { call() }
        response.string("error")?.let { throw ChatBackendException(it) }
        return response
    }

    internal companion object {
        const val NOT_ON_WHATSAPP = "not on WhatsApp"
        const val DISCONNECTED = "WhatsApp disconnected"
        const val NOT_LINKED = "WhatsApp not linked"
        const val CHECK_FAILED = "couldn't check WhatsApp"

        /** The person is waiting on this; the bridge's own timeout is 30s. */
        const val REACH_TIMEOUT_MS = 10_000L
    }
}
