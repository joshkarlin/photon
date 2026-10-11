package app.photon.signal

import android.util.Log
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.names.LocalContacts
import app.photon.data.repository.conversationsFlow
import app.photon.data.repository.messagesFlow
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.findMessageByPrefix
import app.photon.signal.db.getContact
import app.photon.signal.db.getConversationWithContact
import app.photon.signal.db.getConversationsWithContacts
import app.photon.signal.db.getGroupPeople
import app.photon.signal.db.getMessages
import app.photon.signal.db.getReactions
import app.photon.signal.db.getUnsyncedIncomingReads
import app.photon.signal.db.markConversationSeen
import app.photon.signal.db.markMessagesRead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext

/**
 * Signal chats and messages. Names are resolved as they're read
 * ([SignalNames]), so they follow both the stored sources and the LP3
 * address book.
 *
 * @param selfAci the account's own ACI, whose DM is "Note to Self"
 */
class SignalRepository(
    private val db: SignalMessageDatabase,
    private val sender: SignalMessageSender?,
    scope: CoroutineScope,
    localContacts: LocalContacts,
    selfAci: () -> String?,
) {
    companion object {
        private const val TAG = "SignalRepository"
    }

    private val unsyncableReads = UnsyncableReads()
    private val names = SignalNames(db, localContacts::nameFor, selfAci)

    // A write to the database, or an LP3 contact saved or edited.
    private val nameChanges: Flow<Unit> = merge(db.changes, localContacts.changes)

    val conversations: StateFlow<List<Conversation>?> =
        conversationsFlow(scope, TAG, nameChanges) {
            db.getConversationsWithContacts().map { (conversation, contact) -> names.resolve(conversation, contact) }
        }

    fun messages(conversationJid: String, limit: Int = 50): Flow<List<Message>> =
        messagesFlow(
            changes = db.changes,
            load = { db.getMessages(conversationJid, limit) },
            loadReactions = { db.getReactions(it) },
            lookupReply = { db.findMessageByPrefix(it) },
        )

    /** The conversation, its name resolved. */
    fun getConversation(jid: String): Conversation? =
        db.getConversationWithContact(jid)?.let { (conversation, contact) -> names.resolve(conversation, contact) }

    fun getContactPhone(aci: String): String? = db.getContact(aci)?.phone

    /**
     * A group's sender names by ACI, re-resolved whenever a source changes
     * (LocalContacts' changes emit at once, so this reads on subscription too).
     */
    fun participantNames(conversationJid: String): Flow<Map<String, String>> =
        nameChanges
            .map {
                withContext(Dispatchers.IO) {
                    db.getGroupPeople(conversationJid).associate { (id, contact) -> id to names.person(id, contact) }
                }
            }
            .distinctUntilChanged()

    // Each send returns whether it went out. A message that didn't is left
    // "failed" in its thread for the user to retry.

    suspend fun sendMessage(jid: String, text: String, replyToId: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            sender?.sendTextMessage(jid, text, replyToId) ?: false
        }

    suspend fun retryMessage(messageId: String): Boolean =
        withContext(Dispatchers.IO) {
            sender?.retryMessage(messageId) ?: false
        }

    suspend fun deleteMessage(jid: String, messageId: String, forEveryone: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            sender?.deleteMessage(jid, messageId, forEveryone) ?: false
        }

    suspend fun sendReaction(jid: String, messageId: String, emoji: String): Boolean =
        withContext(Dispatchers.IO) {
            sender?.sendReaction(jid, messageId, emoji) ?: false
        }

    /**
     * Clear the chat's unread count and sync the read to the user's other
     * devices. Returns false when that sync was due but didn't go out.
     */
    suspend fun markRead(jid: String): Boolean =
        withContext(Dispatchers.IO) {
            db.markConversationSeen(jid)
            // Sync read state to our other devices so the primary's unread
            // badge clears. Rows only flip from 'seen' to 'read' when they
            // were in a payload that was actually sent, so failures — and
            // rows that can't be expressed in a read sync — retry on the
            // next mark-read instead of being marked read silently.
            val plan = planReadSync(db.getUnsyncedIncomingReads(jid))
            if (unsyncableReads.firstReport(jid, plan)) {
                Log.w(TAG, "Cannot sync read state for ${plan.skippedIds.size} message(s) without an ACI key")
            }
            if (plan.entries.isEmpty()) return@withContext true
            val synced = sender?.sendReadSync(plan.entries) == true
            if (synced) db.markMessagesRead(plan.rowIds)
            synced
        }

    suspend fun sendMedia(jid: String, filePath: String, mimeType: String, caption: String?, replyToId: String?): Boolean =
        withContext(Dispatchers.IO) {
            sender?.sendMediaMessage(jid, filePath, mimeType, caption, replyToId) ?: false
        }
}
