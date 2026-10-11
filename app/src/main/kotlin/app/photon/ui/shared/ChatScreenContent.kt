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
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import app.photon.data.ComposeStyle
import app.photon.data.KeyboardStyle
import app.photon.data.PhotonPreferences
import app.photon.data.model.Message
import app.photon.data.model.Platform
import app.photon.data.names.DisplayNames
import app.photon.ui.shared.components.CallRow
import app.photon.ui.shared.components.ComposeEditor
import app.photon.ui.shared.components.MessageActions
import app.photon.ui.shared.components.MessageInputBar
import app.photon.ui.shared.components.MessageRow
import app.photon.ui.shared.components.SwipeToReply
import app.photon.ui.shared.components.ThreadBottomBar
import app.photon.ui.shared.components.backButton
import app.photon.ui.shared.components.isCall
import app.photon.ui.shared.components.quoteLabel
import app.photon.ui.shared.components.rememberMediaInput
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
 * Handles the top bar, message list and input. Messages are laid out as in
 * the LP3 thread ([MessageRow]); groups add the sender above each incoming
 * burst, as the LP2 group thread does.
 *
 * The input follows Settings › Chat: COMPOSE puts an inline bar under the
 * thread, or (FULL SCREEN) a LightOS bottom bar whose compose action opens a
 * full-screen editor; KEYBOARD writes with the system keyboard or the LP3
 * keyboard, in either style.
 *
 * @param platform whose naming rules group senders and quotes follow
 * @param participantNames resolved names of a group's senders, by JID
 * @param conversationKey identifies the chat (its JID or address), so
 *   per-chat state such as the compose draft can't carry over to another
 *   chat shown in the same place
 */
@Composable
fun ChatScreenContent(
    title: String,
    platform: Platform,
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
    conversationKey: String? = null,
) {
    val messages by messagesFlow.collectAsState(initial = emptyList())
    val context = LocalContext.current
    // Null until the settings load, so no input style flashes first.
    val prefs = remember(context) { PhotonPreferences(context) }
    val composeStyle by remember(prefs) { prefs.composeStyle }.collectAsState(initial = null)
    val keyboardStyle by remember(prefs) { prefs.keyboardStyle }.collectAsState(initial = null)
    ChatThread(
        title = title,
        platform = platform,
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
        composeStyle = composeStyle,
        keyboardStyle = keyboardStyle,
        conversationKey = conversationKey,
    )
}

@Composable
internal fun ChatThread(
    title: String,
    platform: Platform,
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
    composeStyle: ComposeStyle? = ComposeStyle.INLINE,
    keyboardStyle: KeyboardStyle? = KeyboardStyle.SYSTEM,
    conversationKey: String? = null,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // LightOS has no navigation bar to put the keyboard away, so tapping the
    // thread or turning the dial does it.
    val dismissKeyboard = remember(focusManager, keyboardController) {
        {
            focusManager.clearFocus()
            keyboardController?.hide()
            Unit
        }
    }
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    var actionsFor by remember { mutableStateOf<Message?>(null) }
    var replyingTo by remember { mutableStateOf<Message?>(null) }

    // One draft per conversation, shared by both input styles. FULL SCREEN
    // compose is an overlay over the thread, so Back from it keeps the draft.
    val fullScreen = composeStyle == ComposeStyle.FULL_SCREEN
    val draft = rememberSaveable(conversationKey, saver = TextFieldState.Saver) { TextFieldState() }
    var composing by rememberSaveable(conversationKey) { mutableStateOf(false) }
    val editorOpen = fullScreen && composing
    val openCompose = {
        dismissKeyboard()
        composing = true
    }

    // Every send path consumes and clears the active reply target.
    val media = rememberMediaInput(
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
    val sendText = { text: String ->
        onSendText(text, replyingTo?.id)
        replyingTo = null
    }
    // Who a group message is from; in a DM the title already names them.
    val senderName = remember(isGroup, platform, participantNames) {
        { msg: Message ->
            if (isGroup) DisplayNames.sender(platform, msg.isFromMe, participantNames[msg.senderJid], msg.senderJid) else null
        }
    }
    fun replyLabel(target: Message) = quoteLabel(target, senderName(target))

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

    ChatScrollDialEffect(listState, enabled = !editorOpen, onTurn = dismissKeyboard)

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
                    .fillMaxWidth()
                    .observeTaps(dismissKeyboard),
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
                            senderName = senderName,
                            supportsReply = supportsReply,
                            onReply = {
                                replyingTo = msg
                                if (fullScreen) openCompose()
                            },
                            onMediaTap = { onMediaTap?.invoke(msg) },
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
                ReplyChip(label = replyLabel(target), onCancel = { replyingTo = null })
            }

            if (keyboardStyle != null) {
                when (composeStyle) {
                    ComposeStyle.INLINE -> MessageInputBar(draft, keyboardStyle, onSendText = sendText, media = media)
                    ComposeStyle.FULL_SCREEN -> ThreadBottomBar(media = media, onCompose = openCompose)
                    null -> Unit
                }
            }
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

        if (editorOpen) {
            ComposeEditor(
                title = title,
                draft = draft,
                keyboard = keyboardStyle ?: KeyboardStyle.DEFAULT,
                replyLabel = replyingTo?.let(::replyLabel),
                onSend = { text ->
                    sendText(text)
                    draft.clearText()
                    dismissKeyboard()
                    composing = false
                },
                onBack = {
                    dismissKeyboard()
                    composing = false
                },
            )
        }
    }
}

@Composable
private fun MessageItem(
    msg: Message,
    startsBurst: Boolean,
    // Null in a DM: no sender labels, and quotes don't repeat the title.
    senderName: (Message) -> String?,
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
    SwipeToReply(enabled = supportsReply, onReply = onReply) {
        MessageRow(
            msg = msg, startsBurst = startsBurst,
            onMediaTap = onMediaTap, onLongPress = onLongPress,
            senderName = senderName(msg),
            quotedSenderName = msg.replyToPreview?.let(senderName),
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
