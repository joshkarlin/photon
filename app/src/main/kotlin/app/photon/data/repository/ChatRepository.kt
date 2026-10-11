package app.photon.data.repository

import app.photon.data.db.PhotonDatabase
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.BridgeEvent
import app.photon.data.names.LocalContacts
import app.photon.service.BridgeClient
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
import kotlinx.coroutines.withContext

// Go bridge events that mean rendered data changed. The bridge broadcasts
// after each DB write, so these replace the old 500ms poll.
private val REFRESH_EVENTS = setOf(
    "new_message", "message_updated", "conversation_updated", "receipt", "read_self",
    "call_ended", "call_missed", "names_changed",
    "history_sync_progress", "history_sync_complete", "connection_state",
)

/**
 * WhatsApp chats and messages, read from the bridge's database. Names are
 * resolved as they're read ([WhatsAppNames]): the LP3 contact over what the
 * bridge stored, so a change on either side shows on the next refresh.
 */
class ChatRepository(
    private val db: PhotonDatabase,
    private val bridge: BridgeClient,
    scope: CoroutineScope,
    localContacts: LocalContacts,
) {
    val names = WhatsAppNames(localContacts::nameFor)

    // Self-nudge after our own request-based mutations (markRead, reactions):
    // not every bridge request handler broadcasts an event, and the request's
    // response doesn't flow through bridge.events.
    private val localChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val changes: Flow<Unit> = merge(
        bridge.events.filter { it.type in REFRESH_EVENTS }.map { },
        // Events emitted while the bridge was down are gone — re-query
        // on every reconnect.
        bridge.isConnected.drop(1).filter { it }.map { },
        localChanges,
    )

    // What names depend on: the bridge's rows, and the LP3 address book.
    private val nameChanges: Flow<Unit> = merge(changes, localContacts.changes)

    val conversations: StateFlow<List<Conversation>?> =
        conversationsFlow(scope, "ChatRepository", nameChanges) { db.getConversations().map(names::resolve) }

    fun messages(conversationJid: String, limit: Int = 50): Flow<List<Message>> =
        messagesFlow(
            changes = changes,
            load = { db.getMessages(conversationJid, limit) },
            loadReactions = { db.getReactions(it) },
            lookupReply = { db.getMessage(it) },
        )

    /** The conversation, its name resolved. */
    fun getConversation(jid: String): Conversation? = db.getConversation(jid)?.let(names::resolve)

    /** The conversation row as the bridge stored it (name unresolved), for notifications. */
    fun storedConversation(jid: String): Conversation? = db.getConversation(jid)

    // Group sender names, re-read on every refresh signal. Can't ride the
    // conversations StateFlow: participant-name changes don't alter the
    // Conversation rows, so its distinctUntilChanged swallows them. This flow
    // dedupes on the name map itself, so names show as soon as they resolve
    // (e.g. after resolve_participants runs on group open). LocalContacts'
    // changes emit at once, so this reads on subscription too.
    fun participantNames(jid: String): Flow<Map<String, String>> =
        nameChanges
            .map {
                withContext(Dispatchers.IO) {
                    names.participantNames(db.getParticipants(jid), db.getMessageSenders(jid))
                }
            }
            .distinctUntilChanged()

    private fun nudge() {
        localChanges.tryEmit(Unit)
    }

    suspend fun sendMessage(jid: String, text: String, replyToId: String? = null): BridgeEvent {
        return bridge.sendMessage(jid, text, replyToId).also { nudge() }
    }

    suspend fun retryMessage(messageId: String): BridgeEvent {
        return bridge.retryMessage(messageId).also { nudge() }
    }

    suspend fun deleteMessage(jid: String, messageId: String, forEveryone: Boolean): BridgeEvent {
        return bridge.deleteMessage(jid, messageId, forEveryone).also { nudge() }
    }

    suspend fun sendMedia(
        jid: String,
        filePath: String,
        mimeType: String,
        caption: String?,
        replyToId: String?,
        fileName: String? = null,
    ): BridgeEvent {
        return bridge.sendMedia(jid, filePath, mimeType, caption, replyToId, fileName).also { nudge() }
    }

    suspend fun sendReaction(jid: String, messageId: String, senderJid: String, emoji: String): BridgeEvent {
        return bridge.sendReaction(jid, messageId, senderJid, emoji).also { nudge() }
    }

    suspend fun markRead(jid: String, messageIds: List<String>): BridgeEvent {
        return bridge.markRead(jid, messageIds).also { nudge() }
    }

    // Backfill group participant display names from the contact store. The
    // bridge broadcasts conversation_updated when anything changes, which
    // refreshes the conversations flow (and thus participant names) on its own.
    suspend fun resolveParticipants(jid: String): BridgeEvent {
        return bridge.resolveParticipants(jid)
    }
}
