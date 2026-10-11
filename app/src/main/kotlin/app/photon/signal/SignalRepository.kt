package app.photon.signal

import android.util.Log
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.repository.conversationsFlow
import app.photon.data.repository.messagesFlow
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.findMessageByPrefix
import app.photon.signal.db.getContact
import app.photon.signal.db.getConversation
import app.photon.signal.db.getConversations
import app.photon.signal.db.getMessage
import app.photon.signal.db.getMessages
import app.photon.signal.db.getParticipants
import app.photon.signal.db.getReactions
import app.photon.signal.db.getUnsyncedIncomingReads
import app.photon.signal.db.markConversationSeen
import app.photon.signal.db.markMessagesRead
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
    companion object {
        private const val TAG = "SignalRepository"
    }

    private val unsyncableReads = UnsyncableReads()

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

    suspend fun sendReaction(jid: String, messageId: String, emoji: String) {
        withContext(Dispatchers.IO) {
            sender?.sendReaction(jid, messageId, emoji)
        }
    }

    suspend fun markRead(jid: String, messageIds: List<String>) {
        withContext(Dispatchers.IO) {
            db.markConversationSeen(jid)
            val sender = sender ?: return@withContext
            // Sync read state to our other devices so the primary's unread
            // badge clears. Rows only flip from 'seen' to 'read' when they
            // were in a payload that was actually sent, so failures — and
            // rows that can't be expressed in a read sync — retry on the
            // next chat open instead of being marked read silently.
            val plan = planReadSync(db.getUnsyncedIncomingReads(jid))
            if (unsyncableReads.firstReport(jid, plan)) {
                Log.w(TAG, "Cannot sync read state for ${plan.skippedIds.size} message(s) without an ACI key")
            }
            if (plan.entries.isEmpty()) return@withContext
            if (sender.sendReadSync(plan.entries)) {
                db.markMessagesRead(plan.rowIds)
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
