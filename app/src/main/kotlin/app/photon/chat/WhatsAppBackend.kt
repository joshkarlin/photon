package app.photon.chat

import app.photon.data.model.BridgeEvent
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.Platform
import app.photon.data.model.string
import app.photon.data.names.DisplayNames
import app.photon.data.repository.ChatRepository
import app.photon.service.BridgeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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

    // A mark-read whose receipt failed goes out again once WhatsApp is back.
    override val reconnects: Flow<Unit> = bridge.whatsappState.filter { it == "connected" }.map { }

    // On opening a group, backfill any missing sender names from the contact
    // store: historical senders history sync couldn't name. The bridge then
    // broadcasts conversation_updated, which refreshes participantNames.
    override suspend fun onOpen(jid: String, isGroup: Boolean) {
        if (isGroup) request("Name participants") { repository.resolveParticipants(jid) }
    }

    // Phone JIDs carry the number; an @lid DM has none to save.
    override fun contactPhone(jid: String): String? = DisplayNames.phoneOf(jid, Platform.WHATSAPP)

    private suspend fun request(what: String, call: suspend () -> BridgeEvent): BridgeEvent {
        val response = backendCall(what) { call() }
        response.string("error")?.let { throw ChatBackendException(it) }
        return response
    }
}
