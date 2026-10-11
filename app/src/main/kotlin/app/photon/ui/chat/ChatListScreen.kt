package app.photon.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import app.photon.R
import app.photon.chat.ChatBackend
import app.photon.data.model.Conversation
import app.photon.data.model.Platform
import app.photon.service.Sessions
import app.photon.ui.shared.ChatListContent
import app.photon.ui.whatsapp.WhatsAppSyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The chat list: every platform's chats by recency ([filter] null, "All
 * Chats", each with its platform's icon), or one platform's. The filter in
 * the bottom bar opens the platform picker ([onSwitch]).
 */
@Composable
fun ChatListScreen(
    filter: Platform?,
    onChat: (Platform, String) -> Unit,
    onSwitch: () -> Unit,
    onSettings: () -> Unit,
) = ChatListScreen(filter, Sessions::backend, onChat, onSwitch, onSettings)

@Composable
internal fun ChatListScreen(
    filter: Platform?,
    backend: (Platform) -> ChatBackend?,
    onChat: (Platform, String) -> Unit,
    onSwitch: () -> Unit,
    onSettings: () -> Unit,
) {
    val platforms = filter?.let(::listOf) ?: Platform.entries
    // A platform whose session isn't running yet lists nothing; its
    // session appearing recomposes this onto the real list.
    val lists = platforms.map { platform ->
        platform to (backend(platform)?.conversations ?: notRunning).collectAsState().value
    }
    val chats = remember(lists) { mergeChats(lists) }
    val conversations = remember(chats) { chats?.map { it.conversation } }
    val platformByJid = remember(chats) { chats?.associate { it.conversation.jid to it.platform }.orEmpty() }
    fun platformOf(jid: String) = platformByJid[jid] ?: filter ?: Platform.SMS

    ChatListContent(
        filter = filter?.label ?: "All Chats",
        onFilter = onSwitch,
        onSettings = onSettings,
        conversations = conversations,
        platformOf = { platformOf(it.jid) },
        onChat = { jid -> onChat(platformOf(jid), jid) },
        emptyMessage = if (filter == Platform.SIGNAL) "no messages yet" else "no messages",
        emptyContent = if (filter == Platform.WHATSAPP) {
            { WhatsAppSyncStatus() }
        } else null,
        icon = if (filter == null) {
            { conv -> painterResource(iconOf(platformOf(conv.jid))) }
        } else null,
    )
}

/** A listed chat and the platform it's on. */
internal data class ListedChat(val conversation: Conversation, val platform: Platform)

/**
 * Each platform's chats in one list, newest first; null while no platform
 * has read its chats yet. A platform still loading shows nothing yet.
 */
internal fun mergeChats(lists: List<Pair<Platform, List<Conversation>?>>): List<ListedChat>? {
    if (lists.all { it.second == null }) return null
    return lists
        .flatMap { (platform, list) -> list.orEmpty().map { ListedChat(it, platform) } }
        .sortedByDescending { it.conversation.lastTimestamp }
}

private fun iconOf(platform: Platform): Int = when (platform) {
    Platform.WHATSAPP -> R.drawable.ic_whatsapp
    Platform.SIGNAL -> R.drawable.ic_signal
    Platform.SMS -> R.drawable.ic_sms
}

// Stable, so a list whose session hasn't started doesn't resubscribe.
private val notRunning: StateFlow<List<Conversation>?> = MutableStateFlow(null)
