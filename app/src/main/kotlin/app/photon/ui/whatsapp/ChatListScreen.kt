package app.photon.ui.whatsapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.photon.data.model.Platform
import app.photon.service.Sessions
import app.photon.ui.shared.ChatListContent

@Composable
fun ChatListScreen(onChat: (String) -> Unit, onSwitch: () -> Unit, onSettings: () -> Unit) {
    val repo = Sessions.whatsapp?.repository ?: return
    val rawConversations by repo.conversations.collectAsState()
    val conversations = rawConversations?.filter { conv ->
        !conv.isPseudoChat && conv.lastTimestamp > 0
    }

    ChatListContent(
        title = "WhatsApp",
        onTitleClick = onSwitch,
        onSettings = onSettings,
        conversations = conversations,
        platformOf = { Platform.WHATSAPP },
        onChat = onChat,
        emptyContent = { WhatsAppSyncStatus() },
    )
}
