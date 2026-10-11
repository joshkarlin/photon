package app.photon.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import app.photon.data.model.Message
import app.photon.ui.shared.components.CallRow
import app.photon.ui.shared.components.MessageActions
import app.photon.ui.shared.components.MessageInputBar
import app.photon.ui.shared.components.MessageRow
import app.photon.ui.shared.components.SwipeToReply
import app.photon.ui.shared.components.backButton
import app.photon.ui.shared.components.isCall
import app.photon.ui.shared.components.quoteLabel
import app.photon.ui.shared.components.senderLabel
import app.photon.ui.shared.components.startsBurst
import app.photon.ui.theme.PhotonGrid
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Shared chat screen used by WhatsApp, Signal, and SMS.
 * Handles the top bar, message list and input bar. Messages are laid out as
 * in the LP3 thread ([MessageRow]); groups add the sender above each incoming
 * burst, as the LP2 group thread does.
 */
@Composable
fun ChatScreenContent(
    title: String,
    messagesFlow: Flow<List<Message>>,
    isGroup: Boolean,
    participantNames: Map<String, String> = emptyMap(),
    onSendText: (text: String, replyToId: String?) -> Unit,
    onSendAudio: ((path: String, replyToId: String?) -> Unit)? = null,
    onSendMedia: ((path: String, mime: String, replyToId: String?) -> Unit)? = null,
    onBack: () -> Unit,
    onMediaTap: ((Message) -> Unit)? = null,
    onMessagesLoaded: ((List<Message>) -> Unit)? = null,
    onReact: ((messageId: String, senderJid: String, emoji: String) -> Unit)? = null,
    onRetry: ((messageId: String) -> Unit)? = null,
    onDelete: ((message: Message, forEveryone: Boolean) -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    supportsReply: Boolean = true,
    headerContent: (@Composable () -> Unit)? = null,
) {
    val messages by messagesFlow.collectAsState(initial = emptyList())
    ChatThread(
        title = title,
        messages = messages,
        isGroup = isGroup,
        participantNames = participantNames,
        onSendText = onSendText,
        onSendAudio = onSendAudio,
        onSendMedia = onSendMedia,
        onBack = onBack,
        onMediaTap = onMediaTap,
        onMessagesLoaded = onMessagesLoaded,
        onReact = onReact,
        onRetry = onRetry,
        onDelete = onDelete,
        onTitleClick = onTitleClick,
        supportsReply = supportsReply,
        headerContent = headerContent,
    )
}

@Composable
internal fun ChatThread(
    title: String,
    messages: List<Message>,
    isGroup: Boolean,
    participantNames: Map<String, String>,
    onSendText: (text: String, replyToId: String?) -> Unit,
    onSendAudio: ((path: String, replyToId: String?) -> Unit)?,
    onSendMedia: ((path: String, mime: String, replyToId: String?) -> Unit)?,
    onBack: () -> Unit,
    onMediaTap: ((Message) -> Unit)?,
    onMessagesLoaded: ((List<Message>) -> Unit)?,
    onReact: ((messageId: String, senderJid: String, emoji: String) -> Unit)?,
    onRetry: ((messageId: String) -> Unit)?,
    onDelete: ((message: Message, forEveryone: Boolean) -> Unit)?,
    onTitleClick: (() -> Unit)?,
    supportsReply: Boolean,
    headerContent: (@Composable () -> Unit)?,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    var actionsFor by remember { mutableStateOf<Message?>(null) }
    var replyingTo by remember { mutableStateOf<Message?>(null) }

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

    ChatScrollDialEffect(listState)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background)
                .imePadding(),
        ) {
            LightTopBar(
                leftButton = backButton(onBack),
                center = LightTopBarCenter.Text(title, onClick = onTitleClick),
            )

            headerContent?.invoke()

            // Messages arrive newest-first; reverseLayout puts the newest at
            // the bottom, so the message drawn above index i is i + 1.
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(
                    start = PhotonGrid.CHAT_MARGIN.gridUnitsAsDp(),
                    end = PhotonGrid.CHAT_MARGIN.gridUnitsAsDp(),
                    bottom = 0.5f.gridUnitsAsDp(),
                ),
            ) {
                itemsIndexed(messages, key = { _, msg -> msg.id }) { index, msg ->
                    val burstStart = startsBurst(msg, messages.getOrNull(index + 1))
                    val gap = if (burstStart) PhotonGrid.BURST_GAP else PhotonGrid.IN_BURST_GAP
                    Box(modifier = Modifier.padding(top = gap.gridUnitsAsDp())) {
                        MessageItem(
                            msg = msg,
                            startsBurst = burstStart,
                            isGroup = isGroup,
                            title = title,
                            participantNames = participantNames,
                            supportsReply = supportsReply,
                            onReply = { replyingTo = msg },
                            onMediaTap = {
                                keyboardController?.hide()
                                onMediaTap?.invoke(msg)
                            },
                            onLongPress = { actionsFor = msg },
                            onReplyPreviewTap = msg.replyToPreview?.let { quoted ->
                                {
                                    val idx = messages.indexOfFirst { it.id == quoted.id }
                                    if (idx >= 0) {
                                        scrollScope.launch { listState.animateScrollToItem(idx) }
                                    }
                                }
                            },
                            onRetry = onRetry?.let { cb -> { cb(msg.id) } },
                        )
                    }
                }
            }

            // Reply chip (above input) — shown when the user has picked a message to reply to.
            replyingTo?.let { target ->
                ReplyChip(
                    label = quoteLabel(
                        quoted = target,
                        quotedSenderName = participantNames[target.senderJid],
                        jidFallbackChars = 10,
                    ),
                    onCancel = { replyingTo = null },
                )
            }

            // Input bar. All three send paths consume and clear the active reply target.
            MessageInputBar(
                onSendText = { text ->
                    onSendText(text, replyingTo?.id)
                    replyingTo = null
                },
                onSendAudio = onSendAudio?.let { handler ->
                    { path ->
                        handler(path, replyingTo?.id)
                        replyingTo = null
                    }
                },
                onSendMedia = onSendMedia?.let { handler ->
                    { path, mime ->
                        handler(path, mime, replyingTo?.id)
                        replyingTo = null
                    }
                },
            )
        }

        // Message actions (react + delete), shown on long-press.
        actionsFor?.let { msg ->
            MessageActions(
                message = msg,
                canReact = onReact != null && !isCall(msg) &&
                    msg.status != "failed" && msg.status != "sending",
                canDelete = onDelete != null,
                onPick = { emoji ->
                    onReact?.invoke(msg.id, msg.senderJid, emoji)
                    actionsFor = null
                },
                onDelete = { forEveryone ->
                    onDelete?.invoke(msg, forEveryone)
                    actionsFor = null
                },
                onDismiss = { actionsFor = null },
            )
        }
    }
}

@Composable
private fun MessageItem(
    msg: Message,
    startsBurst: Boolean,
    isGroup: Boolean,
    title: String,
    participantNames: Map<String, String>,
    supportsReply: Boolean,
    onReply: () -> Unit,
    onMediaTap: () -> Unit,
    onLongPress: () -> Unit,
    onReplyPreviewTap: (() -> Unit)?,
    onRetry: (() -> Unit)?,
) {
    if (isCall(msg)) {
        CallRow(msg, onLongPress = onLongPress)
        return
    }
    val senderName = participantNames[msg.senderJid]
    val quotedSenderName = msg.replyToPreview?.let { q ->
        when {
            q.isFromMe -> null
            // DMs don't populate participantNames — the conversation
            // title is the only other party, so use that.
            else -> participantNames[q.senderJid] ?: title.takeIf { !isGroup }
        }
    }
    SwipeToReply(enabled = supportsReply, onReply = onReply) {
        MessageRow(
            msg = msg, startsBurst = startsBurst,
            onMediaTap = onMediaTap, onLongPress = onLongPress,
            senderName = if (isGroup) senderLabel(msg, senderName) else null,
            quotedSenderName = quotedSenderName,
            onReplyPreviewTap = onReplyPreviewTap,
            onRetry = onRetry,
        )
    }
}

@Composable
private fun ReplyChip(label: String, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PhotonGrid.CHAT_MARGIN.gridUnitsAsDp())
            .padding(top = 0.25f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Detail,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        LightIcon(
            icon = LightIcons.CLOSE,
            size = 1.5f,
            contentDescription = "Cancel reply",
            modifier = Modifier
                .padding(start = 1f.gridUnitsAsDp())
                .lightClickable(onClick = onCancel),
        )
    }
}
