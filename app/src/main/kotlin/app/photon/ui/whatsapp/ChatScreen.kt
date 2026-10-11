package app.photon.ui.whatsapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.photon.data.model.Message
import app.photon.data.model.Platform
import app.photon.data.names.DisplayNames
import app.photon.data.model.string
import app.photon.service.Sessions
import app.photon.ui.shared.ChatScreenContent
import app.photon.ui.shared.VisibleConversationEffect
import app.photon.ui.shared.components.MediaViewer
import kotlinx.coroutines.launch

/**
 * The mark-read request for a chat's messages (newest first): the newest
 * incoming row's id plus up to 20 incoming ids, or null when that one is
 * already marked. Call rows count: a missed call is unread like a message, so
 * a chat whose newest row is a call must still be marked read. The bridge
 * leaves them out of the receipt it sends WhatsApp (their ids are Photon's
 * own, or call logs WhatsApp takes no receipt for).
 */
internal fun markReadRequest(messages: List<Message>, lastMarkedReadId: String?): Pair<String, List<String>>? {
    val incoming = messages.filter { !it.isFromMe }
    val newestId = incoming.firstOrNull()?.id ?: return null
    if (newestId == lastMarkedReadId) return null
    return newestId to incoming.take(20).map { it.id }
}

@Composable
fun ChatScreen(jid: String, onContact: (phone: String, name: String) -> Unit, onBack: () -> Unit) {
    val repo = Sessions.whatsapp?.repository ?: return
    val scope = rememberCoroutineScope()
    // Observe conversations so the title and participant names refresh live as
    // new messages arrive (e.g. a group member's name learned after open, or
    // history-sync names landing). Falls back to a one-shot read before the hot
    // flow emits.
    val convs by repo.conversations.collectAsState()
    val conversation = convs?.firstOrNull { it.jid == jid } ?: remember(jid) { repo.getConversation(jid) }
    val isGroup = conversation?.isGroup ?: jid.contains("@g.us")
    var viewingMedia by remember { mutableStateOf<Message?>(null) }
    // Newest incoming message id the bridge has confirmed read, so we don't
    // re-send markRead for the same messages on every list emission. Set only
    // once a request succeeds: a failed one (bridge restarting, WhatsApp
    // offline) is retried on the next list change or when WhatsApp reconnects.
    var lastMarkedReadId by remember(jid) { mutableStateOf<String?>(null) }
    var markReadInFlight by remember(jid) { mutableStateOf(false) }
    var latestMessages by remember(jid) { mutableStateOf<List<Message>>(emptyList()) }

    fun markRead() {
        if (markReadInFlight) return
        val (newestId, ids) = markReadRequest(latestMessages, lastMarkedReadId) ?: return
        markReadInFlight = true
        scope.launch {
            try {
                if (repo.markRead(jid, ids).string("error") == null) lastMarkedReadId = newestId
            } catch (_: Exception) {
            } finally {
                markReadInFlight = false
            }
            // Catch up on messages that arrived while the request was out.
            if (lastMarkedReadId == newestId) markRead()
        }
    }

    val waState = Sessions.whatsapp?.bridge?.whatsappState?.collectAsState()?.value
    LaunchedEffect(waState) {
        if (waState == "connected") markRead()
    }

    // Participant names as a flow so they refresh when names resolve (the
    // conversations flow can't carry these — see ChatRepository.participantNames).
    val participantNames by remember(jid) { repo.participantNames(jid) }
        .collectAsState(initial = emptyMap())

    // On opening a group, backfill any missing sender names from the contact
    // store — covers historical senders that history sync couldn't name (and
    // groups that were misclassified before a re-pair). The bridge broadcasts
    // conversation_updated, which refreshes participantNames above.
    LaunchedEffect(jid, isGroup) {
        if (isGroup) {
            try { repo.resolveParticipants(jid) } catch (_: Exception) {}
        }
    }

    val title = DisplayNames.title(jid, conversation?.name, isGroup, Platform.WHATSAPP)

    VisibleConversationEffect(jid)

    ChatScreenContent(
        title = title,
        platform = Platform.WHATSAPP,
        conversationKey = jid,
        messagesFlow = repo.messages(jid),
        isGroup = isGroup,
        participantNames = participantNames,
        onSendText = { msg, replyToId -> scope.launch { repo.sendMessage(jid, msg, replyToId) } },
        onSendAudio = { path, replyToId -> scope.launch { repo.sendMedia(jid, path, "audio/ogg", null, replyToId) } },
        onSendMedia = { path, mime, replyToId -> scope.launch { repo.sendMedia(jid, path, mime, null, replyToId) } },
        onBack = onBack,
        onMediaTap = { msg -> viewingMedia = msg },
        onMessagesLoaded = { messages ->
            latestMessages = messages
            markRead()
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
                // Phone JIDs carry the number; an @lid DM has none to save.
                DisplayNames.phoneOf(jid, Platform.WHATSAPP)?.let { phone -> onContact(phone, title) }
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
                val resp = Sessions.whatsapp!!.bridge.downloadMedia(id)
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
