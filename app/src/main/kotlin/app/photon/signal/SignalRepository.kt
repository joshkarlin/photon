package app.photon.signal

import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.signal.db.SignalMessageDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

class SignalRepository(
    private val db: SignalMessageDatabase,
    private val credentials: SignalCredentials,
    var sender: SignalMessageSender? = null,
    scope: CoroutineScope,
) {
    val conversations: StateFlow<List<Conversation>?> = flow<List<Conversation>?> {
        var last: List<Conversation>? = null
        var consecutiveEmpty = 0
        while (true) {
            try {
                val current = db.getConversations()
                if (current.isEmpty() && !last.isNullOrEmpty()) {
                    consecutiveEmpty++
                    if (consecutiveEmpty < 5) {
                        emit(last!!)
                    } else {
                        last = current
                        emit(current)
                    }
                } else {
                    consecutiveEmpty = 0
                    last = current
                    emit(current)
                }
            } catch (_: Throwable) {
                last?.let { emit(it) }
            }
            delay(500)
        }
    }.flowOn(Dispatchers.IO)
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun messages(conversationJid: String, limit: Int = 50): Flow<List<Message>> = flow {
        // Reply preview cache: quote prefixes are immutable identifiers, so
        // caching avoids the N+1 prefix-lookup every poll tick.
        val replyCache = HashMap<String, Message?>()
        while (true) {
            try {
                val messages = db.getMessages(conversationJid, limit)
                val messageIds = messages.map { it.id }
                val reactions = db.getReactions(messageIds)
                val enriched = messages.map { msg ->
                    val preview = msg.replyToId?.let { prefix ->
                        replyCache.getOrPut(prefix) { db.findMessageByPrefix(prefix) }
                    }
                    msg.copy(
                        reactions = reactions[msg.id] ?: emptyList(),
                        replyToPreview = preview,
                    )
                }
                emit(enriched)
            } catch (_: Throwable) {
                // Transient DB error — keep the flow alive.
            }
            delay(500)
        }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    fun getConversation(jid: String): Conversation? = db.getConversation(jid)

    fun getContactPhone(aci: String): String? = db.getContact(aci)?.phone

    fun getParticipantNames(conversationJid: String): Map<String, String> =
        db.getParticipants(conversationJid).associate {
            it.jid to (it.displayName ?: it.jid.take(8))
        }

    fun getMessage(id: String): Message? = db.getMessage(id)

    fun isPaired(): Boolean = credentials.isRegistered()

    suspend fun sendMessage(jid: String, text: String, replyToId: String? = null) {
        withContext(Dispatchers.IO) {
            sender?.sendTextMessage(jid, text, replyToId)
        }
    }

    suspend fun retryMessage(messageId: String) {
        withContext(Dispatchers.IO) {
            sender?.retryTextMessage(messageId)
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
