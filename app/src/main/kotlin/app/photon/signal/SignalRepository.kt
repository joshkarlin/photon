package app.photon.signal

import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.repository.conversationsFlow
import app.photon.data.repository.messagesFlow
import app.photon.signal.db.SignalMessageDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class SignalRepository(
    private val db: SignalMessageDatabase,
    var sender: SignalMessageSender? = null,
    scope: CoroutineScope,
) {
    val conversations: StateFlow<List<Conversation>?> =
        conversationsFlow(scope, "SignalRepository", db.changes) { db.getConversations() }

    fun messages(conversationJid: String, limit: Int = 50): Flow<List<Message>> =
        messagesFlow(
            changes = db.changes,
            load = { db.getMessages(conversationJid, limit) },
            loadReactions = { db.getReactions(it) },
            lookupReply = { db.findMessageByPrefix(it) },
        )

    fun getConversation(jid: String): Conversation? = db.getConversation(jid)

    fun getContactPhone(aci: String): String? = db.getContact(aci)?.phone

    fun getParticipantNames(conversationJid: String): Map<String, String> =
        db.getParticipants(conversationJid).associate {
            it.jid to (it.displayName ?: it.jid.take(8))
        }

    fun getMessage(id: String): Message? = db.getMessage(id)

    suspend fun sendMessage(jid: String, text: String, replyToId: String? = null) {
        withContext(Dispatchers.IO) {
            sender?.sendTextMessage(jid, text, replyToId)
        }
    }

    suspend fun retryMessage(messageId: String) {
        withContext(Dispatchers.IO) {
            sender?.retryMessage(messageId)
        }
    }

    suspend fun deleteMessage(jid: String, messageId: String, forEveryone: Boolean) {
        withContext(Dispatchers.IO) {
            sender?.deleteMessage(jid, messageId, forEveryone)
        }
    }

    suspend fun sendReaction(jid: String, messageId: String, senderJid: String, emoji: String) {
        // TODO
    }

    suspend fun markRead(jid: String, messageIds: List<String>) {
        db.resetUnread(jid)
        val sender = sender ?: return
        withContext(Dispatchers.IO) {
            // Sync read state to our other devices so the primary's unread
            // badge clears. Message ids encode "{authorAci}_{timestampMs}_…",
            // which is exactly the (author, sent-timestamp) pair the sync
            // carries. Rows only flip to status='read' after a successful
            // send, so failures retry on the next chat open.
            val pending = db.getUnsyncedIncomingReads(jid)
            if (pending.isEmpty()) return@withContext
            val reads = pending.mapNotNull { MessageKeys.parse(it) }
            if (sender.sendReadSync(reads)) {
                db.markMessagesRead(pending)
            }
        }
    }

    suspend fun sendMedia(jid: String, filePath: String, mimeType: String, caption: String?, replyToId: String?) {
        withContext(Dispatchers.IO) {
            sender?.sendMediaMessage(jid, filePath, mimeType, caption, replyToId)
        }
    }

    suspend fun downloadMedia(messageId: String): String? = withContext(Dispatchers.IO) {
        app.photon.service.PhotonService._signalReceiver?.downloadAttachment(messageId)
    }
}
