package app.photon.data.repository

import app.photon.data.db.PhotonDatabase
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.WsEvent
import app.photon.service.WsClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow

class ChatRepository(
    private val db: PhotonDatabase,
    private val ws: WsClient,
) {
    // Trigger to force a re-query (e.g., after receiving a new_message event)
    private val refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun triggerRefresh() {
        refreshTrigger.tryEmit(Unit)
    }

    fun conversations(): Flow<List<Conversation>> = flow {
        while (true) {
            emit(db.getConversations())
            delay(500)
        }
    }

    fun messages(conversationJid: String, limit: Int = 50): Flow<List<Message>> = flow {
        while (true) {
            val messages = db.getMessages(conversationJid, limit)
            val messageIds = messages.map { it.id }
            val reactions = db.getReactions(messageIds)
            // Hydrate reply previews. Look up each reply_to_id once and cache.
            val replyPreviews: Map<String, Message> = messages
                .mapNotNull { it.replyToId }
                .distinct()
                .mapNotNull { id -> db.getMessage(id)?.let { id to it } }
                .toMap()
            val enriched = messages.map { msg ->
                msg.copy(
                    reactions = reactions[msg.id] ?: emptyList(),
                    replyToPreview = msg.replyToId?.let { replyPreviews[it] },
                )
            }
            emit(enriched)
            delay(500)
        }
    }

    fun getConversation(jid: String): Conversation? = db.getConversation(jid)

    fun getMessage(id: String): Message? = db.getMessage(id)

    fun isPaired(): Boolean = db.isPaired()

    suspend fun sendMessage(jid: String, text: String, replyToId: String? = null): WsEvent {
        return ws.sendMessage(jid, text, replyToId)
    }

    suspend fun sendMedia(jid: String, filePath: String, mimeType: String, caption: String?, replyToId: String?): WsEvent {
        return ws.sendMedia(jid, filePath, mimeType, caption, replyToId)
    }

    suspend fun sendReaction(jid: String, messageId: String, senderJid: String, emoji: String): WsEvent {
        return ws.sendReaction(jid, messageId, senderJid, emoji)
    }

    suspend fun markRead(jid: String, messageIds: List<String>): WsEvent {
        return ws.markRead(jid, messageIds)
    }

    suspend fun editMessage(jid: String, messageId: String, newText: String): WsEvent {
        return ws.editMessage(jid, messageId, newText)
    }

    suspend fun sendTyping(jid: String, composing: Boolean): WsEvent {
        return ws.sendTyping(jid, composing)
    }

    suspend fun downloadMedia(messageId: String): WsEvent {
        return ws.downloadMedia(messageId)
    }

    suspend fun requestPairingCode(phone: String): WsEvent {
        return ws.requestPairingCode(phone)
    }

    suspend fun requestQr(): WsEvent {
        return ws.requestQr()
    }

    suspend fun logout(): WsEvent {
        return ws.logout()
    }
}
