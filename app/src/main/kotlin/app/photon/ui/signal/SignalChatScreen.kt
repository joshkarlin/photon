package app.photon.ui.signal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.photon.data.model.Message
import app.photon.service.NotificationHelper
import app.photon.service.PhotonService
import app.photon.ui.shared.ChatScreenContent
import app.photon.ui.shared.components.MediaViewer
import kotlinx.coroutines.launch

@Composable
fun SignalChatScreen(jid: String, onContact: (phone: String, name: String) -> Unit, onBack: () -> Unit) {
    val repo = PhotonService._signalRepository ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Observe the conversations StateFlow so the title updates live when
    // the conversation row is re-resolved (e.g. after the user saves a
    // contact via the in-app editor). Falls back to a one-shot read if the
    // hot StateFlow hasn't emitted yet.
    val convs by repo.conversations.collectAsState()
    val conversation = convs?.firstOrNull { it.jid == jid } ?: remember(jid) { repo.getConversation(jid) }
    val isGroup = conversation?.isGroup ?: false
    val title = conversation?.name?.takeIf { it.isNotBlank() } ?: jid.take(8) + "..."
    var viewingMedia by remember { mutableStateOf<Message?>(null) }
    // Participant names are written by the receiver as group messages come
    // in. Re-read on every conversation update so a new sender's name shows
    // without a navigation away/back.
    val participantNames = remember(jid, convs) {
        if (isGroup) repo.getParticipantNames(jid) else emptyMap()
    }

    ChatScreenContent(
        title = title,
        messagesFlow = repo.messages(jid),
        isGroup = isGroup,
        participantNames = participantNames,
        onSendText = { msg, replyToId -> scope.launch { repo.sendMessage(jid, msg, replyToId) } },
        onSendAudio = { path, replyToId -> scope.launch { repo.sendMedia(jid, path, "audio/ogg", null, replyToId) } },
        onSendMedia = { path, mime, replyToId -> scope.launch { repo.sendMedia(jid, path, mime, null, replyToId) } },
        onBack = onBack,
        onMediaTap = { msg -> viewingMedia = msg },
        onMessagesLoaded = { messages ->
            NotificationHelper.cancelForConversation(context, jid)
            scope.launch { repo.markRead(jid, messages.filter { !it.isFromMe }.map { it.id }) }
        },
        // onReact intentionally omitted: Signal reaction-send isn't implemented
        // yet (SignalRepository.sendReaction is a no-op), so leaving it null hides
        // the reaction picker instead of presenting a dead control.
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
            onDownloadMedia = { id -> repo.downloadMedia(id) },
            onDismiss = { viewingMedia = null },
        )
    }
}
