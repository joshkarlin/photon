package app.photon.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.photon.data.MessageLayout
import app.photon.data.PhotonPreferences
import app.photon.data.ScrollSpeed
import app.photon.data.model.Message
import app.photon.ui.shared.components.CleanMessageRow
import app.photon.ui.shared.components.MessageInputBar
import app.photon.ui.shared.components.SwipeToReply
import app.photon.ui.shared.components.TerminalMessageRow
import app.photon.ui.shared.components.TranscriptMessageRow
import kotlinx.coroutines.flow.Flow

/**
 * Shared chat screen used by WhatsApp, Signal, and SMS.
 * Handles header, message list, layout selection, and input bar.
 */
@Composable
fun ChatScreenContent(
    title: String,
    messagesFlow: Flow<List<Message>>,
    isGroup: Boolean,
    participantNames: Map<String, String> = emptyMap(),
    onSendText: (text: String, replyToId: String?) -> Unit,
    onSendAudio: (path: String, replyToId: String?) -> Unit = { _, _ -> },
    onSendMedia: ((path: String, mime: String, replyToId: String?) -> Unit)? = null,
    onBack: () -> Unit,
    onMediaTap: ((Message) -> Unit)? = null,
    onMessagesLoaded: ((List<Message>) -> Unit)? = null,
    onReact: ((messageId: String, senderJid: String, emoji: String) -> Unit)? = null,
    onRetry: ((messageId: String) -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    supportsReply: Boolean = true,
) {
    val context = LocalContext.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val messages by messagesFlow.collectAsState(initial = emptyList())
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    var reactingTo by remember { mutableStateOf<Message?>(null) }
    var replyingTo by remember { mutableStateOf<Message?>(null) }
    val prefs = PhotonPreferences(context)
    val chatScrollSpeed by prefs.chatScrollSpeed.collectAsState(initial = ScrollSpeed.MEDIUM)
    val scrollPx = with(androidx.compose.ui.platform.LocalDensity.current) { chatScrollSpeed.dp.dp.toPx() }
    val layout by (if (isGroup) prefs.groupLayout else prefs.dmLayout)
        .collectAsState(initial = if (isGroup) MessageLayout.TRANSCRIPT else MessageLayout.TERMINAL)

    // Notify caller when messages load (e.g., for mark-as-read)
    LaunchedEffect(messages) {
        if (messages.isNotEmpty()) {
            onMessagesLoaded?.invoke(messages)
        }
    }

    // Auto-scroll on new messages (keyed on newest message ID, not list size,
    // so it fires even if pruning keeps the count stable)
    val newestMessageId = messages.firstOrNull()?.id
    LaunchedEffect(newestMessageId) {
        if (newestMessageId != null) {
            listState.animateScrollToItem(0)
        }
    }

    ScrollDialEffect { dir -> listState.scrollBy(-dir * scrollPx) } // negated for reverseLayout

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .imePadding(),
    ) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 12.dp),
        ) {
            Text("<", fontSize = 18.sp, color = Color(0xFF666666),
                modifier = Modifier.align(Alignment.CenterStart).clickable(onClick = onBack).padding(end = 16.dp))
            val titleMod = if (onTitleClick != null) {
                Modifier.align(Alignment.Center).clickable(onClick = onTitleClick)
            } else {
                Modifier.align(Alignment.Center)
            }
            Text(
                text = title.uppercase(),
                fontSize = 13.sp, letterSpacing = 3.sp, color = Color(0xFF666666),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = titleMod,
            )
        }
        HorizontalDivider(color = Color(0xFF1A1A1A))

        // Messages
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            state = listState,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                val senderName = participantNames[msg.senderJid]
                val quotedSenderName = msg.replyToPreview?.let { q ->
                    when {
                        q.isFromMe -> null
                        // DMs don't populate participantNames — the conversation
                        // title is the only other party, so use that.
                        else -> participantNames[q.senderJid] ?: title.takeIf { !isGroup }
                    }
                }
                val handleMediaTap = {
                    keyboardController?.hide()
                    onMediaTap?.invoke(msg)
                    Unit
                }
                val handleLongPress = { reactingTo = msg }
                val handleReplyPreviewTap: (() -> Unit)? = msg.replyToPreview?.let { quoted ->
                    {
                        val idx = messages.indexOfFirst { it.id == quoted.id }
                        if (idx >= 0) {
                            scrollScope.launch { listState.animateScrollToItem(idx) }
                        }
                    }
                }
                val handleRetry: (() -> Unit)? = onRetry?.let { cb ->
                    { cb(msg.id) }
                }
                SwipeToReply(
                    enabled = supportsReply,
                    onReply = { replyingTo = msg },
                ) {
                    when (layout) {
                        MessageLayout.TERMINAL -> TerminalMessageRow(
                            msg = msg, isGroup = isGroup, onMediaTap = handleMediaTap,
                            onLongPress = handleLongPress, senderName = senderName,
                            quotedSenderName = quotedSenderName,
                            onReplyPreviewTap = handleReplyPreviewTap,
                            onRetry = handleRetry,
                        )
                        MessageLayout.CLEAN -> CleanMessageRow(
                            msg = msg, isGroup = isGroup, onMediaTap = handleMediaTap,
                            onLongPress = handleLongPress, senderName = senderName,
                            quotedSenderName = quotedSenderName,
                            onReplyPreviewTap = handleReplyPreviewTap,
                            onRetry = handleRetry,
                        )
                        MessageLayout.TRANSCRIPT -> TranscriptMessageRow(
                            msg = msg, isGroup = isGroup, senderName = senderName,
                            onMediaTap = handleMediaTap, onLongPress = handleLongPress,
                            quotedSenderName = quotedSenderName,
                            onReplyPreviewTap = handleReplyPreviewTap,
                            onRetry = handleRetry,
                        )
                    }
                }
            }
        }

        // Reply chip (above input) — shown when the user has picked a message to reply to.
        replyingTo?.let { target ->
            val quotedName = when {
                target.isFromMe -> "You"
                else -> participantNames[target.senderJid]
                    ?: target.senderJid.substringBefore("@").take(10)
            }
            val preview = target.textBody?.takeIf { it.isNotBlank() }?.replace('\n', ' ')
                ?: "[ ${target.contentType} ]"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D0D0D))
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "↳ $quotedName: ${preview.take(60)}",
                    fontSize = 11.sp,
                    color = Color(0xFF666666),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "✕",
                    fontSize = 14.sp,
                    color = Color(0xFF666666),
                    modifier = Modifier
                        .clickable { replyingTo = null }
                        .padding(start = 12.dp),
                )
            }
        }

        // Input bar. All three send paths consume and clear the active reply target.
        MessageInputBar(
            onSendText = { text ->
                onSendText(text, replyingTo?.id)
                replyingTo = null
            },
            onSendAudio = { path ->
                onSendAudio(path, replyingTo?.id)
                replyingTo = null
            },
            onSendMedia = onSendMedia?.let { handler ->
                { path, mime ->
                    handler(path, mime, replyingTo?.id)
                    replyingTo = null
                }
            },
        )
    }

    // Reaction picker overlay
    reactingTo?.let { msg ->
        ReactionPicker(
            onPick = { emoji ->
                onReact?.invoke(msg.id, msg.senderJid, emoji)
                reactingTo = null
            },
            onDismiss = { reactingTo = null },
        )
    }
}

@Composable
private fun ReactionPicker(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val emojis = listOf("❤️", "👍", "😂", "😮", "😢", "🙏")
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .background(Color(0xFF0D0D0D))
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            emojis.forEach { emoji ->
                Text(
                    text = emoji,
                    fontSize = 28.sp,
                    modifier = Modifier
                        .clickable { onPick(emoji) }
                        .padding(8.dp),
                )
            }
        }
    }
}
