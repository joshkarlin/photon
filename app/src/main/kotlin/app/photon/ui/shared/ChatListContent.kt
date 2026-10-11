package app.photon.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.style.TextAlign
import app.photon.data.model.Conversation
import app.photon.service.NotificationHelper
import app.photon.ui.shared.components.backButton
import app.photon.ui.shared.components.settingsButton
import app.photon.ui.theme.PhotonGrid
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Shared chat list screen structure used by WhatsApp, Signal, SMS, and All Chats.
 * Handles the top bar, loading state, empty state, and conversation list.
 */
@Composable
fun ChatListContent(
    title: String,
    conversations: List<Conversation>?,
    onChat: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    emptyMessage: String = "No messages",
    emptyContent: @Composable (() -> Unit)? = null,
    icon: @Composable ((Conversation) -> Painter)? = null,
    onTitleClick: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    ListScrollDialEffect(listState)

    // Clear notifications left behind for chats that are no longer unread
    // (read on another device, deleted, or posted by a previous process).
    LaunchedEffect(conversations) {
        conversations?.let { list ->
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
        title = title,
        conversations = conversations,
        onChat = onChat,
        listState = listState,
        onBack = onBack,
        emptyMessage = emptyMessage,
        emptyContent = emptyContent,
        icon = icon,
        onTitleClick = onTitleClick,
        onSettings = onSettings,
    )
}

/** The chat list without its effects, so previews can render it. */
@Composable
internal fun ChatListLayout(
    title: String,
    conversations: List<Conversation>?,
    onChat: (String) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    onBack: (() -> Unit)? = null,
    emptyMessage: String = "No messages",
    emptyContent: @Composable (() -> Unit)? = null,
    icon: @Composable ((Conversation) -> Painter)? = null,
    onTitleClick: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(
            leftButton = onBack?.let(::backButton),
            // The ▾ marks the title as the platform picker.
            center = LightTopBarCenter.Text(
                text = if (onTitleClick != null) "$title ▾" else title,
                onClick = onTitleClick,
            ),
            rightButton = onSettings?.let(::settingsButton),
        )

        when {
            // Loading — show nothing
            conversations == null -> {}

            // Empty — show custom content or default message
            conversations.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = PhotonGrid.MENU_MARGIN.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    if (emptyContent != null) {
                        emptyContent()
                    } else {
                        LightText(emptyMessage, variant = LightTextVariant.Copy, align = TextAlign.Center)
                    }
                }
            }

            // Conversations list
            else -> {
                LightLazyScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = PhotonGrid.MENU_MARGIN.gridUnitsAsDp()),
                    listState = listState,
                    uniformItemHeightGridUnits = PhotonGrid.LIST_ROW_HEIGHT,
                ) {
                    items(conversations, key = { it.jid }) { conv ->
                        ConversationRow(
                            conv = conv,
                            onClick = { onChat(conv.jid) },
                            icon = icon?.invoke(conv),
                        )
                    }
                }
            }
        }
    }
}
