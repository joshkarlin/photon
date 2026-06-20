package app.photon.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import app.photon.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.photon.data.ListScrollSpeed
import app.photon.data.PhotonPreferences
import app.photon.data.model.Conversation
import app.photon.ui.shared.components.PhotonHeader

/**
 * Shared chat list screen structure used by WhatsApp, Signal, SMS, and All Chats.
 * Handles header, loading state, empty state, and conversation list.
 */
@Composable
fun ChatListContent(
    title: String,
    conversations: List<Conversation>?,
    onChat: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    emptyMessage: String = "NO MESSAGES",
    emptyContent: @Composable (() -> Unit)? = null,
    icon: @Composable ((Conversation) -> Painter)? = null,
    onTitleClick: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val prefs = remember { PhotonPreferences(context) }
    val menuScrollSpeed by prefs.menuScrollSpeed
        .collectAsState(initial = ListScrollSpeed.SLOW)
    ScrollDialEffect { dir ->
        val step = dir.toInt() * menuScrollSpeed.items
        val target = (listState.firstVisibleItemIndex + step).coerceAtLeast(0)
        listState.scrollToItem(target)
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

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black),
    ) {
        // Header — same dimensions as every other screen header.
        PhotonHeader(
            title = if (onTitleClick != null) "$title ▾" else title,
            onBack = onBack,
            onTitleClick = onTitleClick,
            trailing = {
                if (onSettings != null) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = "Settings",
                        tint = Color(0xFF666666),
                        modifier = Modifier.align(Alignment.CenterEnd)
                            .clickable(onClick = onSettings)
                            .padding(start = 16.dp)
                            .size(18.dp),
                    )
                }
            },
        )
        HorizontalDivider(color = Color(0xFF1A1A1A))

        when {
            // Loading — show nothing
            conversations == null -> {}

            // Empty — show custom content or default message
            conversations.isEmpty() -> {
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    if (emptyContent != null) {
                        emptyContent()
                    } else {
                        Text(emptyMessage, fontSize = 18.sp, letterSpacing = 3.sp, color = Color.White)
                    }
                }
            }

            // Conversations list
            else -> {
                LazyColumn(state = listState) {
                    items(conversations, key = { it.jid }) { conv ->
                        ConversationRow(
                            conv = conv,
                            onClick = { onChat(conv.jid) },
                            icon = icon?.invoke(conv),
                        )
                        HorizontalDivider(color = Color(0xFF111111))
                    }
                }
            }
        }
    }
}
