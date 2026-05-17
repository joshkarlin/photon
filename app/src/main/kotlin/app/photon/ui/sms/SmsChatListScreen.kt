package app.photon.ui.sms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.photon.service.PhotonService
import app.photon.ui.shared.ChatListContent

@Composable
fun SmsChatListScreen(onChat: (String) -> Unit, onSwitch: () -> Unit, onSettings: () -> Unit) {
    val repo = PhotonService._smsRepository ?: return
    val conversations by repo.conversations.collectAsState()

    ChatListContent(
        title = "SMS",
        onTitleClick = onSwitch,
        onSettings = onSettings,
        conversations = conversations,
        onChat = onChat,
    )
}
