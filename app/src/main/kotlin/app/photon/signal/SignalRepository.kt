package app.photon.signal

import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.signal.db.SignalMessageDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class SignalRepository(
    private val db: SignalMessageDatabase,
    private val credentials: SignalCredentials,
    var sender: SignalMessageSender? = null,
) {
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
            // Reply quotes are stored as the stable "authorAci_timestamp" prefix.
            val replyPreviews: Map<String, Message> = messages
                .mapNotNull { it.replyToId }
                .distinct()
                .mapNotNull { prefix -> db.findMessageByPrefix(prefix)?.let { prefix to it } }
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

    fun isPaired(): Boolean = credentials.isRegistered()

    suspend fun sendMessage(jid: String, text: String, replyToId: String? = null) {
        withContext(Dispatchers.IO) {
            sender?.sendTextMessage(jid, text, replyToId)
        }
    }

    suspend fun sendReaction(jid: String, messageId: String, senderJid: String, emoji: String) {
        // TODO
    }

    suspend fun markRead(jid: String, messageIds: List<String>) {
        db.resetUnread(jid)
    }

    suspend fun sendTyping(jid: String, composing: Boolean) {
        // TODO
    }

    suspend fun sendMedia(jid: String, filePath: String, mimeType: String, caption: String?, replyToId: String?) {
        // TODO
    }

    suspend fun downloadMedia(messageId: String): String? {
        // TODO
        return null
    }

    suspend fun logout() {
        credentials.clear()
    }
}
