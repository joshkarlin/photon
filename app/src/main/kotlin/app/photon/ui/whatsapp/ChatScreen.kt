package app.photon.ui.whatsapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.photon.data.model.Message
import app.photon.data.model.string
import app.photon.service.NotificationHelper
import app.photon.service.PhotonService
import app.photon.ui.shared.ChatScreenContent
import app.photon.ui.shared.components.MediaViewer
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
    // Observe conversations so the title and participant names refresh live as
    // new messages arrive (e.g. a group member's name learned after open, or
    // history-sync names landing). Falls back to a one-shot read before the hot
    // flow emits.
    val convs by repo.conversations.collectAsState()
    val conversation = convs?.firstOrNull { it.jid == jid } ?: remember(jid) { repo.getConversation(jid) }
    val isGroup = conversation?.isGroup ?: jid.contains("@g.us")
    var viewingMedia by remember { mutableStateOf<Message?>(null) }
    // Newest incoming message id we've already marked read, so we don't
    // re-send markRead for the same messages on every list emission.
    var lastMarkedReadId by remember(jid) { mutableStateOf<String?>(null) }

    // Load participant names for group chats — re-read on every conversation
    // update so a sender's name shows once it's known, without navigating away.
    val db = PhotonService._database
    val participantNames = remember(jid, convs) {
        if (isGroup && db != null) {
            db.getParticipants(jid).associate { it.jid to (it.displayName ?: it.jid.substringBefore("@")) }
        } else emptyMap()
    }

    // On opening a group, backfill any missing sender names from the contact
    // store — covers historical senders that history sync couldn't name (and
    // groups that were misclassified before a re-pair). The bridge broadcasts
    // conversation_updated, which refreshes participantNames above.
    LaunchedEffect(jid, isGroup) {
        if (isGroup) {
            try { repo.resolveParticipants(jid) } catch (_: Exception) {}
        }
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
            // messages arrive newest-first; only mark read when a new
            // incoming message appears.
            val newestIncomingId = messages.firstOrNull { !it.isFromMe }?.id
            if (newestIncomingId != null && newestIncomingId != lastMarkedReadId) {
                lastMarkedReadId = newestIncomingId
                val unreadIds = messages.filter { !it.isFromMe }.take(20).map { it.id }
                scope.launch { try { repo.markRead(jid, unreadIds) } catch (_: Exception) {} }
            }
        },
        onReact = { messageId, senderJid, emoji ->
            scope.launch { try { repo.sendReaction(jid, messageId, senderJid, emoji) } catch (_: Exception) {} }
        },
        onRetry = { messageId ->
            scope.launch { try { repo.retryMessage(messageId) } catch (_: Exception) {} }
        },
        onDelete = { message, forEveryone ->
            scope.launch { try { repo.deleteMessage(jid, message.id, forEveryone) } catch (_: Exception) {} }
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
                // Surface the bridge's actual error (e.g. expired CDN media)
                // instead of letting the strict success-deserializer throw an
                // opaque JSON parse error that hides the real cause.
                resp.string("error")?.let { throw RuntimeException(it) }
                resp.string("local_path")
            },
            onDismiss = { viewingMedia = null },
        )
    }
}
