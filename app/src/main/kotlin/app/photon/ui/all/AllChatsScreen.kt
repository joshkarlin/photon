package app.photon.ui.all

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import app.photon.R
import app.photon.data.model.Conversation
import app.photon.service.PhotonService
import app.photon.ui.shared.ChatListContent
import kotlinx.coroutines.flow.StateFlow

enum class Platform { WHATSAPP, SIGNAL, SMS }

private data class TaggedConversation(
    val conversation: Conversation,
    val platform: Platform,
)

// Stable singleton "empty" flow used when a repository is briefly unavailable.
// PhotonService's repository singletons are backed by mutableStateOf, so when
// a repo becomes non-null, Compose invalidates this composable automatically
// and the binding switches from emptyConvFlow to the real StateFlow.
private val emptyConvFlow: StateFlow<List<Conversation>?> =
    kotlinx.coroutines.flow.MutableStateFlow(null)

@Composable
fun AllChatsScreen(
    onChat: (Platform, String) -> Unit,
    onSwitch: () -> Unit,
    onSettings: () -> Unit,
) {
    val waConvs by (PhotonService._chatRepository?.conversations ?: emptyConvFlow).collectAsState()
    val sigConvs by (PhotonService._signalRepository?.conversations ?: emptyConvFlow).collectAsState()
    val smsConvs by (PhotonService._smsRepository?.conversations ?: emptyConvFlow).collectAsState()

    val anyLoaded = waConvs != null || sigConvs != null || smsConvs != null

    val allChats = remember(waConvs, sigConvs, smsConvs) {
        if (!anyLoaded) null else buildList {
            (waConvs ?: emptyList()).filter { c ->
                !c.isPseudoChat && c.lastTimestamp > 0
            }.forEach { add(TaggedConversation(it, Platform.WHATSAPP)) }
            (sigConvs ?: emptyList()).forEach { add(TaggedConversation(it, Platform.SIGNAL)) }
            (smsConvs ?: emptyList()).forEach { add(TaggedConversation(it, Platform.SMS)) }
        }.sortedByDescending { it.conversation.lastTimestamp }
    }

    val platformMap = remember(allChats) {
        allChats?.associate { it.conversation.jid to it.platform } ?: emptyMap()
    }

    ChatListContent(
        title = "ALL CHATS",
        onTitleClick = onSwitch,
        onSettings = onSettings,
        conversations = allChats?.map { it.conversation },
        onChat = { jid ->
            val platform = platformMap[jid] ?: Platform.WHATSAPP
            onChat(platform, jid)
        },
        icon = { conv ->
            painterResource(when (platformMap[conv.jid]) {
                Platform.WHATSAPP -> R.drawable.ic_whatsapp
                Platform.SIGNAL -> R.drawable.ic_signal
                Platform.SMS -> R.drawable.ic_sms
                null -> R.drawable.ic_sms
            })
        },
    )
}
