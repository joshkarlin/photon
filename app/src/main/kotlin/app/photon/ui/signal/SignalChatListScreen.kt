package app.photon.ui.signal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.photon.service.PhotonService
import app.photon.ui.shared.ChatListContent

@Composable
fun SignalChatListScreen(onChat: (String) -> Unit, onSwitch: () -> Unit, onSettings: () -> Unit) {
    val repo = PhotonService._signalRepository ?: return
    val conversations by repo.conversations.collectAsState()

    ChatListContent(
        title = "SIGNAL",
        onTitleClick = onSwitch,
        onSettings = onSettings,
        conversations = conversations,
        onChat = onChat,
        emptyMessage = "NO MESSAGES YET",
    )
}
