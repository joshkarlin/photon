package app.photon.data.repository

import app.photon.data.db.PhotonDatabase
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.WsEvent
import app.photon.service.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

// Go bridge events that mean rendered data changed. The bridge broadcasts
// after each DB write, so these replace the old 500ms poll.
private val REFRESH_EVENTS = setOf(
    "new_message", "message_updated", "conversation_updated", "receipt",
    "history_sync_progress", "history_sync_complete", "connection_state",
)

class ChatRepository(
    private val db: PhotonDatabase,
    private val ws: WsClient,
    scope: CoroutineScope,
) {
    // Self-nudge after our own request-based mutations (markRead, reactions):
    // not every bridge request handler broadcasts an event, and the request's
    // response doesn't flow through ws.events.
    private val localChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val changes: Flow<Unit> = merge(
        ws.events.filter { it.type in REFRESH_EVENTS }.map { },
        // Events emitted while the bridge socket was down are gone — re-query
        // on every reconnect.
        ws.isConnected.drop(1).filter { it }.map { },
        localChanges,
    )

    val conversations: StateFlow<List<Conversation>?> =
        conversationsFlow(scope, "ChatRepository", changes) { db.getConversations() }

    fun messages(conversationJid: String, limit: Int = 50): Flow<List<Message>> =
        messagesFlow(
            changes = changes,
            load = { db.getMessages(conversationJid, limit) },
            loadReactions = { db.getReactions(it) },
            lookupReply = { db.getMessage(it) },
        )

    fun getConversation(jid: String): Conversation? = db.getConversation(jid)

    // Group participant display names, re-read on every refresh signal. Can't
    // ride the conversations StateFlow: participant-name changes don't alter the
    // Conversation rows, so its distinctUntilChanged swallows them. This flow
    // dedupes on the name map itself, so names show as soon as they resolve
    // (e.g. after resolve_participants runs on group open). Empty for DMs.
    fun participantNames(jid: String): Flow<Map<String, String>> =
        changes
            .onStart { emit(Unit) }
            .map {
                withContext(Dispatchers.IO) {
                    db.getParticipants(jid)
                        .associate { p -> p.jid to (p.displayName ?: p.jid.substringBefore("@")) }
                }
            }
            .distinctUntilChanged()

    private fun nudge() {
        localChanges.tryEmit(Unit)
    }

    suspend fun sendMessage(jid: String, text: String, replyToId: String? = null): WsEvent {
        return ws.sendMessage(jid, text, replyToId).also { nudge() }
    }

    suspend fun retryMessage(messageId: String): WsEvent {
        return ws.retryMessage(messageId).also { nudge() }
    }

    suspend fun deleteMessage(jid: String, messageId: String, forEveryone: Boolean): WsEvent {
        return ws.deleteMessage(jid, messageId, forEveryone).also { nudge() }
    }

    suspend fun sendMedia(jid: String, filePath: String, mimeType: String, caption: String?, replyToId: String?): WsEvent {
        return ws.sendMedia(jid, filePath, mimeType, caption, replyToId).also { nudge() }
    }

    suspend fun sendReaction(jid: String, messageId: String, senderJid: String, emoji: String): WsEvent {
        return ws.sendReaction(jid, messageId, senderJid, emoji).also { nudge() }
    }

    suspend fun markRead(jid: String, messageIds: List<String>): WsEvent {
        return ws.markRead(jid, messageIds).also { nudge() }
    }

    // Backfill group participant display names from the contact store. The
    // bridge broadcasts conversation_updated when anything changes, which
    // refreshes the conversations flow (and thus participant names) on its own.
    suspend fun resolveParticipants(jid: String): WsEvent {
        return ws.resolveParticipants(jid)
    }
}
