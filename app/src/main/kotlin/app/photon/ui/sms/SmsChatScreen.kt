package app.photon.ui.sms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import app.photon.data.model.Platform
import app.photon.data.names.DisplayNames
import app.photon.service.PhotonService
import app.photon.ui.shared.ChatScreenContent
import app.photon.ui.shared.VisibleConversationEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SmsChatScreen(address: String, onContact: (phone: String, name: String) -> Unit, onBack: () -> Unit) {
    val repo = PhotonService._smsRepository ?: return
    val scope = rememberCoroutineScope()
    // Track the conversation reactively via the StateFlow so the title
    // updates when a contact is saved or edited (LocalContacts).
    val convs by repo.conversations.collectAsState()
    val conversation = convs?.firstOrNull { it.jid == address }
        ?: remember(address) { repo.getConversation(address) }
    val title = DisplayNames.title(address, conversation?.name, isGroup = false, Platform.SMS)
    VisibleConversationEffect(address)

    // SMS "address" IS the phone number for normal SMS; for alphanumeric
    // shortcodes (HSBC, Specsavers etc.) it isn't a dialable number and we
    // shouldn't offer adding it as a contact.
    val looksLikePhone = DisplayNames.phoneOf(address, Platform.SMS) != null

    ChatScreenContent(
        title = title,
        platform = Platform.SMS,
        conversationKey = address,
        messagesFlow = repo.messages(address),
        isGroup = false,
        onSendText = { text, _ -> scope.launch(Dispatchers.IO) { repo.sendMessage(address, text) } },
        onBack = onBack,
        onMessagesLoaded = { _ ->
            repo.markAsRead(address)
        },
        onRetry = { messageId ->
            scope.launch(Dispatchers.IO) { repo.retryMessage(messageId) }
        },
        onTitleClick = if (looksLikePhone) {
            { onContact(address, title) }
        } else null,
        supportsReply = false,
    )
}
