package app.photon.ui.signal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.photon.data.model.Platform
import app.photon.service.PhotonService
import app.photon.ui.shared.ChatListContent

@Composable
fun SignalChatListScreen(onChat: (String) -> Unit, onSwitch: () -> Unit, onSettings: () -> Unit) {
    val repo = PhotonService._signalRepository ?: return
    val conversations by repo.conversations.collectAsState()

    ChatListContent(
        title = "Signal",
        onTitleClick = onSwitch,
        onSettings = onSettings,
        conversations = conversations,
        platformOf = { Platform.SIGNAL },
        onChat = onChat,
        emptyMessage = "No messages yet",
    )
}
