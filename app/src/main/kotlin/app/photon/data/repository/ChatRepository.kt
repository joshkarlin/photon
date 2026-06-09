package app.photon.data.repository

import app.photon.data.db.PhotonDatabase
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.WsEvent
import app.photon.service.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class ChatRepository(
    private val db: PhotonDatabase,
    private val ws: WsClient,
    scope: CoroutineScope,
) {
    val conversations: StateFlow<List<Conversation>?> =
        pollingConversationsFlow(scope, "ChatRepository") { db.getConversations() }

    fun messages(conversationJid: String, limit: Int = 50): Flow<List<Message>> =
        pollingMessagesFlow(
            load = { db.getMessages(conversationJid, limit) },
            loadReactions = { db.getReactions(it) },
            lookupReply = { db.getMessage(it) },
        )

    fun getConversation(jid: String): Conversation? = db.getConversation(jid)

    suspend fun sendMessage(jid: String, text: String, replyToId: String? = null): WsEvent {
        return ws.sendMessage(jid, text, replyToId)
    }

    suspend fun retryMessage(messageId: String): WsEvent {
        return ws.retryMessage(messageId)
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
}
