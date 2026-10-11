package app.photon.ui.signal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.photon.data.model.Message
import app.photon.data.model.Platform
import app.photon.data.names.DisplayNames
import app.photon.service.Sessions
import app.photon.ui.shared.ChatScreenContent
import app.photon.ui.shared.VisibleConversationEffect
import app.photon.ui.shared.components.MediaViewer
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun SignalChatScreen(jid: String, onContact: (phone: String, name: String) -> Unit, onBack: () -> Unit) {
    val repo = Sessions.signal?.repository ?: return
    val scope = rememberCoroutineScope()
    // Observe the conversations StateFlow so the title updates live when
    // the conversation row is re-resolved (e.g. after the user saves a
    // contact via the in-app editor). Falls back to a one-shot read if the
    // hot StateFlow hasn't emitted yet.
    val convs by repo.conversations.collectAsState()
    val conversation = convs?.firstOrNull { it.jid == jid } ?: remember(jid) { repo.getConversation(jid) }
    val isGroup = conversation?.isGroup ?: false
    val title = DisplayNames.title(jid, conversation?.name, isGroup, Platform.SIGNAL)
    var viewingMedia by remember { mutableStateOf<Message?>(null) }
    VisibleConversationEffect(jid)
    // Group sender names, re-resolved as names are learned or LP3 contacts change.
    val participantNames by remember(jid, isGroup) {
        if (isGroup) repo.participantNames(jid) else flowOf(emptyMap())
    }.collectAsState(initial = emptyMap())

    ChatScreenContent(
        title = title,
        platform = Platform.SIGNAL,
        conversationKey = jid,
        messagesFlow = repo.messages(jid),
        isGroup = isGroup,
        participantNames = participantNames,
        headerContent = {
            repo.sender?.identityStore?.let { store ->
                SignalIdentityNotice(
                    store = store,
                    contacts = if (isGroup) participantNames else mapOf(jid to title),
                )
            }
        },
        onSendText = { msg, replyToId -> scope.launch { repo.sendMessage(jid, msg, replyToId) } },
        onSendAudio = { path, replyToId -> scope.launch { repo.sendMedia(jid, path, "audio/ogg", null, replyToId) } },
        onSendMedia = { path, mime, replyToId -> scope.launch { repo.sendMedia(jid, path, mime, null, replyToId) } },
        onBack = onBack,
        onMediaTap = { msg -> viewingMedia = msg },
        onMessagesLoaded = { messages ->
            scope.launch { repo.markRead(jid) }
        },
        onReact = { messageId, _, emoji ->
            scope.launch { repo.sendReaction(jid, messageId, emoji) }
        },
        onRetry = { messageId ->
            scope.launch { try { repo.retryMessage(messageId) } catch (_: Exception) {} }
        },
        onDelete = { message, forEveryone ->
            scope.launch { try { repo.deleteMessage(jid, message.id, forEveryone) } catch (_: Exception) {} }
        },
        onTitleClick = if (!isGroup) {
            { val phone = repo.getContactPhone(jid); if (phone != null) onContact(phone, title) }
        } else null,
    )

    // Media viewer overlay
    viewingMedia?.let { media ->
        MediaViewer(
            messageId = media.id,
            mediaMime = media.mediaMime,
            existingPath = media.mediaUrl,
            onDownloadMedia = { id -> Sessions.signal!!.backend.downloadMedia(id) },
            onDismiss = { viewingMedia = null },
        )
    }
}
