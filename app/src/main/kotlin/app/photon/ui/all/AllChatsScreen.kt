package app.photon.ui.all

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.painterResource
import app.photon.R
import app.photon.data.model.Conversation
import app.photon.service.PhotonService
import app.photon.ui.shared.ChatListContent

enum class Platform { WHATSAPP, SIGNAL, SMS }

private data class TaggedConversation(
    val conversation: Conversation,
    val platform: Platform,
)

@Composable
fun AllChatsScreen(
    onChat: (Platform, String) -> Unit,
    onBack: () -> Unit,
) {
    val waConvs by (PhotonService._chatRepository?.conversations()
        ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = null)
    val sigConvs by (PhotonService._signalRepository?.conversations()
        ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = null)
    val smsConvs by (PhotonService._smsRepository?.conversations()
        ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = null)

    // null until at least one source has loaded
    val anyLoaded = waConvs != null || sigConvs != null || smsConvs != null

    val allChats = if (!anyLoaded) null else buildList {
        (waConvs ?: emptyList()).filter { c ->
            !c.jid.startsWith("status@") && !c.jid.startsWith("0@") && c.lastTimestamp > 0
        }.forEach { add(TaggedConversation(it, Platform.WHATSAPP)) }
        (sigConvs ?: emptyList()).forEach { add(TaggedConversation(it, Platform.SIGNAL)) }
        (smsConvs ?: emptyList()).forEach { add(TaggedConversation(it, Platform.SMS)) }
    }?.sortedByDescending { it.conversation.lastTimestamp }

    val platformMap = allChats?.associate { it.conversation.jid to it.platform } ?: emptyMap()

    ChatListContent(
        title = "ALL CHATS",
        conversations = allChats?.map { it.conversation },
        onChat = { jid ->
            val platform = platformMap[jid] ?: Platform.WHATSAPP
            onChat(platform, jid)
        },
        onBack = onBack,
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
