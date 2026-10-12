package app.photon.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import app.photon.data.model.Conversation
import app.photon.data.model.Platform
import app.photon.service.NotificationHelper
import app.photon.ui.shared.components.PageScrollBar
import app.photon.ui.shared.components.backButton
import app.photon.ui.shared.components.newMessageButton
import app.photon.ui.shared.components.settingsButton
import app.photon.ui.theme.PhotonGrid
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * What frames a chat list: the main list's bottom bar, or a secondary
 * page's top bar.
 */
sealed interface ChatListFrame {
    /**
     * The main list, as LightOS Messages: no top bar (its height is left
     * empty above the list), and a bottom bar that stays put while the list
     * scrolls, with Settings on the left as in LightOS Calendar and
     * Weather, the [filter] in the middle ("All Chats", "Signal"; tapping
     * it calls [onFilter]), and New message on the right (none when
     * [onNewMessage] is null).
     */
    data class Main(
        val filter: String,
        val onFilter: (() -> Unit)? = null,
        val onSettings: (() -> Unit)? = null,
        val onNewMessage: (() -> Unit)? = null,
    ) : ChatListFrame

    /**
     * A list opened from somewhere else (Archived): Back and a centred
     * [title] on top, as every secondary page has, and no bottom bar.
     */
    data class Page(val title: String, val onBack: () -> Unit) : ChatListFrame
}

/**
 * The chat list's layout and behaviour, for [app.photon.ui.chat.ChatListScreen]:
 * the conversation list (or its loading or empty state) in its [frame].
 *
 * @param platformOf which platform a listed conversation belongs to
 * @param reconcileWith the chats whose notifications this list clears once
 *   they're read: those listed, and any the list leaves out (archived chats
 *   still notify)
 */
@Composable
fun ChatListContent(
    frame: ChatListFrame,
    conversations: List<Conversation>?,
    platformOf: (Conversation) -> Platform,
    onChat: (String) -> Unit,
    emptyMessage: String = "no messages",
    emptyContent: @Composable (() -> Unit)? = null,
    icon: @Composable ((Conversation) -> Painter)? = null,
    reconcileWith: List<Conversation>? = conversations,
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    ListScrollDialEffect(listState)

    // Clear notifications left behind for chats that are no longer unread
    // (read on another device, deleted, or posted by a previous process).
    LaunchedEffect(reconcileWith) {
        reconcileWith?.let { list ->
            NotificationHelper.reconcile(context, list.associate { it.jid to it.unreadCount })
        }
    }

    // When a new message promotes a different chat to the top of the list, keep
    // the user pinned to the top *only* if they were already parked there — so
    // the newly-promoted chat is visible rather than scrolled off above. If they
    // had scrolled down, the keyed LazyColumn preserves their position instead.
    // Processed in a single collector so the reorder frame (top changes and the
    // at-top reading flips together) reads the pre-reorder at-top intent.
    val convState = rememberUpdatedState(conversations)
    LaunchedEffect(Unit) {
        var wasAtTop = true
        var prevTop = convState.value?.firstOrNull()?.jid
        snapshotFlow {
            (convState.value?.firstOrNull()?.jid) to
                (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0)
        }.collect { (top, atTop) ->
            if (top != prevTop) {
                if (wasAtTop && convState.value?.isNotEmpty() == true) {
                    listState.scrollToItem(0)
                    wasAtTop = true
                }
                prevTop = top
            } else {
                wasAtTop = atTop
            }
        }
    }

    ChatListLayout(
        frame = frame,
        conversations = conversations,
        platformOf = platformOf,
        onChat = onChat,
        listState = listState,
        emptyMessage = emptyMessage,
        emptyContent = emptyContent,
        icon = icon,
    )
}

/** Test tag of the chat list's scroll view. */
internal const val CHAT_LIST_TAG = "chatList"

/**
 * The chat list without its effects, so previews can render it: the list,
 * or its loading or empty state, in its [frame]. Loading and empty states
 * keep the frame, so Settings (or Back) is always in reach.
 */
@Composable
internal fun ChatListLayout(
    frame: ChatListFrame,
    conversations: List<Conversation>?,
    platformOf: (Conversation) -> Platform,
    onChat: (String) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    emptyMessage: String = "no messages",
    emptyContent: @Composable (() -> Unit)? = null,
    icon: @Composable ((Conversation) -> Painter)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        when (frame) {
            is ChatListFrame.Main -> Spacer(Modifier.height(PhotonGrid.TOP_BAR_HEIGHT.gridUnitsAsDp()))
            is ChatListFrame.Page -> LightTopBar(
                leftButton = backButton(frame.onBack),
                center = LightTopBarCenter.Text(frame.title),
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                // Loading — show nothing
                conversations == null -> {}

                // Empty — custom content or a short message, lower case as
                // LightOS writes them ("no events")
                conversations.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = PhotonGrid.MARGIN.gridUnitsAsDp()),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (emptyContent != null) {
                            emptyContent()
                        } else {
                            LightText(emptyMessage, variant = LightTextVariant.Copy, align = TextAlign.Center)
                        }
                    }
                }

                // Conversations list: full width, so the scroll bar sits in the
                // right margin by the screen's edge; each row insets itself.
                else -> {
                    LightLazyScrollView(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(CHAT_LIST_TAG),
                        scrollBarPosition = PageScrollBar,
                        listState = listState,
                        uniformItemHeightGridUnits = PhotonGrid.LIST_ROW_HEIGHT,
                    ) {
                        items(conversations, key = { it.jid }) { conv ->
                            ConversationRow(
                                conv = conv,
                                platform = platformOf(conv),
                                onClick = { onChat(conv.jid) },
                                icon = icon?.invoke(conv),
                            )
                        }
                    }
                }
            }
        }

        if (frame is ChatListFrame.Main) {
            LightBottomBar(
                items = listOf(
                    frame.onSettings?.let(::settingsButton),
                    LightBarButton.Text(text = frame.filter.uppercase(), onClick = frame.onFilter),
                    frame.onNewMessage?.let(::newMessageButton),
                ),
            )
        }
    }
}
