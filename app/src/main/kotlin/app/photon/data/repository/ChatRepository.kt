package app.photon.data.repository

import app.photon.data.db.PhotonDatabase
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.WsEvent
import app.photon.service.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

class ChatRepository(
    private val db: PhotonDatabase,
    private val ws: WsClient,
    scope: CoroutineScope,
) {
    // Trigger to force a re-query (e.g., after receiving a new_message event)
    private val refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun triggerRefresh() {
        refreshTrigger.tryEmit(Unit)
    }

    // Hot, eagerly-subscribed chat list. Stays populated across navigation so
    // screens that consume it never see a transient null after first load.
    // Exceptions inside the loop are swallowed so transient DB errors (e.g. the
    // SQLite file being briefly inaccessible during FBE unlock after the screen
    // turns on) don't kill the flow and leave the UI permanently empty until
    // the user kills and relaunches the app.
    val conversations: StateFlow<List<Conversation>?> = flow<List<Conversation>?> {
        var last: List<Conversation>? = null
        var consecutiveErrors = 0
        var consecutiveEmpty = 0
        while (true) {
            try {
                val current = db.getConversations()
                if (consecutiveErrors > 0) {
                    android.util.Log.i("ChatRepository",
                        "DB recovered after $consecutiveErrors errors; got ${current.size} convs")
                    consecutiveErrors = 0
                }
                // Empty when we previously had data is suspicious — could be a
                // legit Reset, but also happens transiently during FBE unlock
                // and WAL checkpoints. Hold the cached value for a few ticks
                // before believing the empty.
                if (current.isEmpty() && !last.isNullOrEmpty()) {
                    consecutiveEmpty++
                    if (consecutiveEmpty < 5) {
                        android.util.Log.w("ChatRepository",
                            "Suspect-empty #$consecutiveEmpty (last had ${last!!.size}); reusing cache")
                        emit(last!!)
                    } else {
                        android.util.Log.i("ChatRepository",
                            "Empty confirmed after $consecutiveEmpty ticks; accepting")
                        last = current
                        emit(current)
                    }
                } else {
                    consecutiveEmpty = 0
                    last = current
                    emit(current)
                }
            } catch (e: Throwable) {
                consecutiveErrors++
                if (consecutiveErrors == 1 || consecutiveErrors % 20 == 0) {
                    android.util.Log.w("ChatRepository",
                        "DB read error #$consecutiveErrors (last had ${last?.size} convs): ${e.message}")
                }
                last?.let { emit(it) }
            }
            delay(500)
        }
    }.flowOn(Dispatchers.IO)
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun messages(conversationJid: String, limit: Int = 50): Flow<List<Message>> = flow {
        // Reply preview cache, scoped to this collector. Quoted messages don't
        // change content (edits are unusual on a quote), so caching by id avoids
        // the N+1 lookup every poll tick.
        val replyCache = HashMap<String, Message?>()
        while (true) {
            try {
                val messages = db.getMessages(conversationJid, limit)
                val messageIds = messages.map { it.id }
                val reactions = db.getReactions(messageIds)
                val enriched = messages.map { msg ->
                    val preview = msg.replyToId?.let { id ->
                        replyCache.getOrPut(id) { db.getMessage(id) }
                    }
                    msg.copy(
                        reactions = reactions[msg.id] ?: emptyList(),
                        replyToPreview = preview,
                    )
                }
                emit(enriched)
            } catch (_: Throwable) {
                // Transient DB error — keep the flow alive and retry on the
                // next tick rather than terminating it.
            }
            delay(500)
        }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    fun getConversation(jid: String): Conversation? = db.getConversation(jid)

    fun getMessage(id: String): Message? = db.getMessage(id)

    fun isPaired(): Boolean = db.isPaired()

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
