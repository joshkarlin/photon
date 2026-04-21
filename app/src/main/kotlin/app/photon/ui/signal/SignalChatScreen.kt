package app.photon.ui.signal

import androidx.compose.runtime.Composable
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
fun SignalChatScreen(jid: String, onBack: () -> Unit) {
    val repo = PhotonService._signalRepository ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val conversation = remember(jid) { repo.getConversation(jid) }
    val isGroup = conversation?.isGroup ?: false
    val title = conversation?.name?.takeIf { it.isNotBlank() } ?: jid.take(8) + "..."
    var viewingMedia by remember { mutableStateOf<Message?>(null) }

    ChatScreenContent(
        title = title,
        messagesFlow = repo.messages(jid),
        isGroup = isGroup,
        onSendText = { msg, replyToId -> scope.launch { repo.sendMessage(jid, msg, replyToId) } },
        onSendAudio = { path, replyToId -> scope.launch { repo.sendMedia(jid, path, "audio/ogg", null, replyToId) } },
        onBack = onBack,
        onMediaTap = { msg -> viewingMedia = msg },
        onMessagesLoaded = { messages ->
            NotificationHelper.cancelForConversation(context, jid)
            scope.launch { repo.markRead(jid, messages.filter { !it.isFromMe }.map { it.id }) }
        },
        onReact = { messageId, senderJid, emoji ->
            scope.launch { try { repo.sendReaction(jid, messageId, senderJid, emoji) } catch (_: Exception) {} }
        },
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
