package app.photon.ui.sms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import app.photon.service.NotificationHelper
import app.photon.service.PhotonService
import app.photon.ui.shared.ChatScreenContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SmsChatScreen(address: String, onBack: () -> Unit) {
    val repo = PhotonService._smsRepository ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val conversation = remember(address) { repo.getConversation(address) }
    val title = conversation?.name ?: address

    ChatScreenContent(
        title = title,
        messagesFlow = repo.messages(address),
        isGroup = false,
        onSendText = { text, _ -> scope.launch(Dispatchers.IO) { repo.sendMessage(address, text) } },
        onBack = onBack,
        onMessagesLoaded = { _ ->
            NotificationHelper.cancelForConversation(context, address)
            repo.markAsRead(address)
        },
        supportsReply = false,
    )
}
