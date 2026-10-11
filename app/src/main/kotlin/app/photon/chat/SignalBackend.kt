package app.photon.chat

import androidx.compose.runtime.Composable
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.Platform
import app.photon.signal.SignalRepository
import app.photon.signal.store.PhotonIdentityKeyStore
import app.photon.ui.signal.SignalIdentityNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Signal through the in-process library. Its sends report whether they went
 * out rather than throwing; one that didn't becomes a [ChatBackendException].
 *
 * @param receiverState the receiver's connection state
 * @param identityStore whose changed safety numbers the chat header shows
 * @param downloadAttachment the receiver's attachment download
 */
class SignalBackend(
    private val repository: SignalRepository,
    receiverState: StateFlow<String>,
    private val identityStore: PhotonIdentityKeyStore,
    private val downloadAttachment: (messageId: String) -> String?,
) : ChatBackend {

    override val platform = Platform.SIGNAL

    override val capabilities = ChatCapabilities(
        reply = true, media = true, audio = true, reactions = true,
        delete = true, deleteForEveryone = true, contactEditing = true,
    )

    override val conversations: StateFlow<List<Conversation>?> get() = repository.conversations

    override fun conversation(jid: String): Conversation? = repository.getConversation(jid)

    override fun messages(jid: String): Flow<List<Message>> = repository.messages(jid)

    override fun participantNames(jid: String): Flow<Map<String, String>> = repository.participantNames(jid)

    override suspend fun sendText(jid: String, text: String, replyToId: String?) {
        succeed("Message not sent") { repository.sendMessage(jid, text, replyToId) }
    }

    override suspend fun sendMedia(jid: String, path: String, mime: String, replyToId: String?, fileName: String?) {
        succeed("Message not sent") { repository.sendMedia(jid, path, mime, null, replyToId, fileName) }
    }

    override suspend fun react(jid: String, messageId: String, senderJid: String, emoji: String) {
        succeed("Reaction not sent") { repository.sendReaction(jid, messageId, emoji) }
    }

    override suspend fun retry(messageId: String) {
        succeed("Message not sent") { repository.retryMessage(messageId) }
    }

    override suspend fun delete(jid: String, messageId: String, forEveryone: Boolean) {
        succeed("Message not deleted") { repository.deleteMessage(jid, messageId, forEveryone) }
    }

    // Signal marks the whole chat seen; what can fail is the read sync to
    // the user's other devices.
    override suspend fun markRead(jid: String, incomingIds: List<String>) {
        succeed("Read not synced") { repository.markRead(jid) }
    }

    override suspend fun downloadMedia(messageId: String): String =
        backendCall("Download") { withContext(Dispatchers.IO) { downloadAttachment(messageId) } }
            ?: throw ChatBackendException("Download not available")

    // Signal reaches only people Photon already knows: an ACI the contacts
    // table maps to the number, from the primary's contact sync or past
    // chats. Finding anyone else would need Signal's contact discovery
    // service (CDSI), which Photon doesn't use; that's out of scope for now.
    override suspend fun reach(phoneE164: String): Reach = withContext(Dispatchers.IO) {
        val aci = runCatching { repository.aciForPhone(phoneE164) }.getOrNull()
            ?: return@withContext Reach.Unreachable(NOT_KNOWN)
        Reach.Reachable(aci, existing = runCatching { repository.hasMessages(aci) }.getOrDefault(false))
    }

    // A read sync that failed offline goes out once Signal is back.
    override val reconnects: Flow<Unit> = receiverState.filter { it == "connected" }.map { }

    // Changed safety numbers of the chat's people: the DM's contact, or a
    // group's members.
    @Composable
    override fun Header(jid: String, isGroup: Boolean, title: String, participantNames: Map<String, String>) {
        SignalIdentityNotice(
            store = identityStore,
            contacts = if (isGroup) participantNames else mapOf(jid to title),
        )
    }

    // From their contacts row: the LP3 contact for its number, the primary's
    // contact sync name, their profile name, or the number.
    override fun newChatTitle(jid: String): String = repository.personName(jid)

    override fun contactPhone(jid: String): String? = repository.getContactPhone(jid)

    private suspend fun succeed(failure: String, call: suspend () -> Boolean) {
        if (!backendCall(failure) { call() }) throw ChatBackendException(failure)
    }

    internal companion object {
        const val NOT_KNOWN = "not known to Signal"
    }
}
