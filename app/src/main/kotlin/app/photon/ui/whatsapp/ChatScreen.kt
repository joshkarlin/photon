package app.photon.ui.whatsapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.photon.data.PhotonPreferences
import app.photon.data.model.Message
import app.photon.service.NotificationHelper
import app.photon.service.PhotonService
import app.photon.ui.shared.ChatScreenContent
import app.photon.ui.shared.components.MediaViewer
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

private fun formatJidAsPhone(jid: String): String {
    val number = jid.substringBefore("@")
    val server = jid.substringAfter("@", "")
    return when {
        server == "s.whatsapp.net" && number.length >= 5 && number.all { it.isDigit() } -> "+$number"
        server == "lid" -> "Contact"
        else -> number
    }
}

@Composable
fun ChatScreen(jid: String, onContact: (phone: String, name: String) -> Unit, onBack: () -> Unit) {
    val repo = PhotonService._chatRepository ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val conversation = remember(jid) { repo.getConversation(jid) }
    val isGroup = jid.contains("@g.us")
    var viewingMedia by remember { mutableStateOf<Message?>(null) }

    // Load participant names for group chats
    val db = PhotonService._database
    val participantNames = remember(jid) {
        if (isGroup && db != null) {
            db.getParticipants(jid).associate { it.jid to (it.displayName ?: it.jid.substringBefore("@")) }
        } else emptyMap()
    }

    val title = conversation?.name?.takeIf { it.isNotBlank() } ?: formatJidAsPhone(jid)

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
            val unreadIds = messages.filter { !it.isFromMe }.take(20).map { it.id }
            if (unreadIds.isNotEmpty()) {
                scope.launch { try { repo.markRead(jid, unreadIds) } catch (_: Exception) {} }
            }
        },
        onReact = { messageId, senderJid, emoji ->
            scope.launch { try { repo.sendReaction(jid, messageId, senderJid, emoji) } catch (_: Exception) {} }
        },
        onRetry = { messageId ->
            scope.launch { try { repo.retryMessage(messageId) } catch (_: Exception) {} }
        },
        onTitleClick = if (!isGroup) {
            {
                // For WhatsApp DMs the phone-shaped JIDs (`12345@s.whatsapp.net`)
                // give us a number directly. LID JIDs don't have a phone in
                // hand here so we hide the affordance.
                val phone = jid.substringBefore("@").takeIf {
                    jid.endsWith("@s.whatsapp.net") && it.length >= 5 && it.all(Char::isDigit)
                }?.let { "+$it" }
                if (phone != null) onContact(phone, title)
            }
        } else null,
    )

    // Media viewer overlay
    viewingMedia?.let { media ->
        MediaViewer(
            messageId = media.id,
            mediaMime = media.mediaMime,
            existingPath = media.mediaUrl,
            onDownloadMedia = { id ->
                val resp = PhotonService.wsClient.downloadMedia(id)
                val parsed = kotlinx.serialization.json.Json.decodeFromJsonElement(app.photon.data.model.DownloadMediaResponse.serializer(), resp.payload)
                parsed.localPath
            },
            onDismiss = { viewingMedia = null },
        )
    }
}
