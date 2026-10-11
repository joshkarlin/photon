package app.photon.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.photon.chat.ChatBackend
import app.photon.chat.ChatBackendException
import app.photon.chat.MarkReadGate
import app.photon.data.model.Message
import app.photon.data.model.Platform
import app.photon.data.names.DisplayNames
import app.photon.service.Sessions
import app.photon.ui.shared.ChatScreenContent
import app.photon.ui.shared.VisibleConversationEffect
import app.photon.ui.shared.components.MediaViewer
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** One conversation on any platform; blank until that platform's session is running. */
@Composable
fun ChatScreen(
    platform: Platform,
    jid: String,
    onContact: (phone: String, name: String) -> Unit,
    onBack: () -> Unit,
) {
    val backend = Sessions.backend(platform) ?: return
    ChatScreen(backend, jid, onContact, onBack)
}

/**
 * A conversation through its platform's [backend]: the shared thread
 * ([ChatScreenContent]) with what the platform supports, the media viewer,
 * and the contact editor from a DM's title.
 */
@Composable
internal fun ChatScreen(
    backend: ChatBackend,
    jid: String,
    onContact: (phone: String, name: String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val capabilities = backend.capabilities
    // Observe the chat list so the title and sender names follow renames as
    // they happen (a group member's name learned after opening, a contact
    // saved in the editor). Read once until the list has the chat.
    val conversations by backend.conversations.collectAsState()
    val conversation = conversations?.firstOrNull { it.jid == jid } ?: remember(backend, jid) { backend.conversation(jid) }
    val isGroup = conversation?.isGroup ?: jid.endsWith("@g.us")
    val title = DisplayNames.title(jid, conversation?.name, isGroup, backend.platform)
    var viewingMedia by remember { mutableStateOf<Message?>(null) }

    // Group sender names, resolved again as names are learned or LP3 contacts change.
    val participantNames by remember(backend, jid, isGroup) {
        if (isGroup) backend.participantNames(jid) else flowOf(emptyMap())
    }.collectAsState(initial = emptyMap())

    val markRead = remember(backend, jid) { MarkReadGate(scope) { ids -> backend.markRead(jid, ids) } }
    LaunchedEffect(backend, jid) {
        backend.reconnects.collect { markRead.retry() }
    }
    LaunchedEffect(backend, jid, isGroup) {
        try {
            backend.onOpen(jid, isGroup)
        } catch (_: ChatBackendException) {
        }
    }
    VisibleConversationEffect(jid)

    // A DM's title opens the contact editor, when it has a number to save.
    val contactPhone = remember(backend, jid, conversation) {
        if (!isGroup && capabilities.contactEditing) backend.contactPhone(jid) else null
    }

    // What the user asks for. A failure needs nothing more here: a send
    // that failed stays in the thread, marked failed, for a retry.
    fun act(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (_: ChatBackendException) {
            }
        }
    }

    ChatScreenContent(
        title = title,
        platform = backend.platform,
        conversationKey = jid,
        messagesFlow = remember(backend, jid) { backend.messages(jid) },
        isGroup = isGroup,
        participantNames = participantNames,
        headerContent = { backend.Header(jid, isGroup, title, participantNames) },
        onSendText = { text, replyToId -> act { backend.sendText(jid, text, replyToId) } },
        onSendAudio = if (capabilities.audio) {
            { path, replyToId -> act { backend.sendAudio(jid, path, replyToId) } }
        } else null,
        onSendMedia = if (capabilities.media) {
            { path, mime, replyToId -> act { backend.sendMedia(jid, path, mime, replyToId) } }
        } else null,
        onBack = onBack,
        onMediaTap = { msg -> viewingMedia = msg },
        onMessagesLoaded = markRead::onMessages,
        onReact = if (capabilities.reactions) {
            { messageId, senderJid, emoji -> act { backend.react(jid, messageId, senderJid, emoji) } }
        } else null,
        onRetry = { messageId -> act { backend.retry(messageId) } },
        onDelete = if (capabilities.delete) {
            { message, forEveryone -> act { backend.delete(jid, message.id, forEveryone) } }
        } else null,
        canDeleteForEveryone = capabilities.deleteForEveryone,
        onTitleClick = contactPhone?.let { phone -> { onContact(phone, title) } },
        supportsReply = capabilities.reply,
    )

    viewingMedia?.let { media ->
        MediaViewer(
            messageId = media.id,
            mediaMime = media.mediaMime,
            existingPath = media.mediaUrl,
            onDownloadMedia = backend::downloadMedia,
            onDismiss = { viewingMedia = null },
        )
    }
}
