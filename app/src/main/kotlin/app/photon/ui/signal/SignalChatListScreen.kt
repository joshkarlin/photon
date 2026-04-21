package app.photon.ui.signal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.photon.service.PhotonService
import app.photon.ui.shared.ChatListContent

@Composable
fun SignalChatListScreen(onChat: (String) -> Unit, onBack: () -> Unit) {
    val repo = PhotonService._signalRepository ?: return
    val conversations by repo.conversations().collectAsState(initial = null)

    ChatListContent(
        title = "SIGNAL",
        conversations = conversations,
        onChat = onChat,
        onBack = onBack,
        emptyMessage = "NO MESSAGES YET",
    )
}
