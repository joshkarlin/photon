package app.photon.chat

import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.Platform
import app.photon.data.names.DisplayNames
import app.photon.sms.SmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * SMS through the system SMS store: text only, no groups, replies,
 * reactions or deletes. Calls it can't make throw [ChatBackendException];
 * the chat screen doesn't offer them (see [capabilities]).
 */
class SmsBackend(private val repository: SmsRepository) : ChatBackend {

    override val platform = Platform.SMS

    override val capabilities = ChatCapabilities(
        reply = false, media = false, audio = false, reactions = false,
        // Only the default SMS app can delete from the SMS store.
        delete = false, deleteForEveryone = false,
        contactEditing = true,
    )

    override val conversations: StateFlow<List<Conversation>?> get() = repository.conversations

    override fun conversation(jid: String): Conversation? = repository.getConversation(jid)

    override fun messages(jid: String): Flow<List<Message>> = repository.messages(jid)

    override fun participantNames(jid: String): Flow<Map<String, String>> = flowOf(emptyMap())

    override suspend fun sendText(jid: String, text: String, replyToId: String?) {
        backendCall("Send") { withContext(Dispatchers.IO) { repository.sendMessage(jid, text) } }
    }

    override suspend fun sendMedia(jid: String, path: String, mime: String, replyToId: String?) = unsupported("media")

    override suspend fun react(jid: String, messageId: String, senderJid: String, emoji: String) = unsupported("reactions")

    override suspend fun retry(messageId: String) {
        backendCall("Retry") { withContext(Dispatchers.IO) { repository.retryMessage(messageId) } }
    }

    override suspend fun delete(jid: String, messageId: String, forEveryone: Boolean) = unsupported("deleting")

    // Read state is Photon's own, so this can't fail on the network.
    override suspend fun markRead(jid: String, incomingIds: List<String>) {
        backendCall("Mark read") { withContext(Dispatchers.IO) { repository.markAsRead(jid) } }
    }

    override suspend fun downloadMedia(messageId: String): String = unsupported("media")

    // A number-keyed chat is its number; an alphanumeric sender ("HSBC UK")
    // isn't one to save.
    override fun contactPhone(jid: String): String? = DisplayNames.phoneOf(jid, Platform.SMS)

    override fun mayHaveContactPhone(jid: String) = contactPhone(jid) != null

    private fun unsupported(what: String): Nothing = throw ChatBackendException("SMS has no $what")
}
