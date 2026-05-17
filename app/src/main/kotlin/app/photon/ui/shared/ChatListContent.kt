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
    val menuScrollSpeed by PhotonPreferences(LocalContext.current).menuScrollSpeed
        .collectAsState(initial = ListScrollSpeed.SLOW)
    ScrollDialEffect { dir ->
        val step = dir.toInt() * menuScrollSpeed.items
        val target = (listState.firstVisibleItemIndex + step).coerceAtLeast(0)
        listState.scrollToItem(target)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black),
    ) {
        // Header — same dimensions as ChatScreenContent and SettingsScreen
        // headers (top=16, bottom=12, 18sp side glyphs, 13sp title).
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 12.dp)) {
            if (onBack != null) {
                Text("<", fontSize = 18.sp, color = Color(0xFF666666),
                    modifier = Modifier.align(Alignment.CenterStart)
                        .clickable(onClick = onBack)
                        .padding(end = 16.dp))
            }
            val titleText = if (onTitleClick != null) "$title ▾" else title
            val titleMod = if (onTitleClick != null) {
                Modifier.align(Alignment.Center).clickable(onClick = onTitleClick)
            } else {
                Modifier.align(Alignment.Center)
            }
            Text(titleText, fontSize = 13.sp, letterSpacing = 3.sp, color = Color(0xFF666666),
                modifier = titleMod)
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
        }
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
