package app.photon.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import app.photon.R
import app.photon.chat.ChatBackend
import app.photon.data.model.Conversation
import app.photon.data.model.Platform
import app.photon.service.Sessions
import app.photon.ui.shared.ChatListContent
import app.photon.ui.shared.ChatListFrame
import app.photon.ui.whatsapp.WhatsAppSyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The chat list: every platform's chats by recency ([filter] null, "All
 * Chats", each with its platform's icon), or one platform's. Archived chats
 * aren't listed ([ArchivedChatsScreen] lists them). The filter in the
 * bottom bar opens the platform picker ([onSwitch]).
 */
@Composable
fun ChatListScreen(
    filter: Platform?,
    onChat: (Platform, String) -> Unit,
    onSwitch: () -> Unit,
    onSettings: () -> Unit,
    onNewMessage: (() -> Unit)? = null,
) = ChatListScreen(filter, Sessions::backend, onChat, onSwitch, onSettings, onNewMessage)

@Composable
internal fun ChatListScreen(
    filter: Platform?,
    backend: (Platform) -> ChatBackend?,
    onChat: (Platform, String) -> Unit,
    onSwitch: () -> Unit,
    onSettings: () -> Unit,
    onNewMessage: (() -> Unit)? = null,
) {
    val all = rememberChats(filter?.let(::listOf) ?: Platform.entries, backend)
    val listed = remember(all) { all?.filter { !it.conversation.isArchived } }
    ListedChats(
        frame = ChatListFrame.Main(
            filter = filter?.label ?: "All Chats",
            onFilter = onSwitch,
            onSettings = onSettings,
            onNewMessage = onNewMessage,
        ),
        chats = listed,
        all = all,
        fallbackPlatform = filter,
        onChat = onChat,
        emptyMessage = if (filter == Platform.SIGNAL) "no messages yet" else "no messages",
        // WhatsApp's sync status explains a list with no chats yet, not one
        // whose chats are all archived.
        emptyContent = if (filter == Platform.WHATSAPP && all.isNullOrEmpty()) {
            { WhatsAppSyncStatus() }
        } else null,
        showIcons = filter == null,
    )
}

/**
 * Chats archived on the user's phone, from every platform, by recency and
 * with platform icons: the chat list's rows under Back and an "Archived"
 * title. Opened from the platform picker.
 */
@Composable
fun ArchivedChatsScreen(
    onChat: (Platform, String) -> Unit,
    onBack: () -> Unit,
) = ArchivedChatsScreen(Sessions::backend, onChat, onBack)

@Composable
internal fun ArchivedChatsScreen(
    backend: (Platform) -> ChatBackend?,
    onChat: (Platform, String) -> Unit,
    onBack: () -> Unit,
) {
    val all = rememberChats(Platform.entries, backend)
    val archived = remember(all) { all?.filter { it.conversation.isArchived } }
    ListedChats(
        frame = ChatListFrame.Page(title = ARCHIVED, onBack = onBack),
        chats = archived,
        all = all,
        fallbackPlatform = null,
        onChat = onChat,
        emptyMessage = "no archived chats",
        emptyContent = null,
        showIcons = true,
    )
}

/** The archived chats' title, and the platform picker's row that opens them. */
const val ARCHIVED = "Archived"

/**
 * [chats] in [frame]. Notifications are reconciled against [all] the
 * platforms' chats, archived or not, since archived chats still notify.
 */
@Composable
private fun ListedChats(
    frame: ChatListFrame,
    chats: List<ListedChat>?,
    all: List<ListedChat>?,
    fallbackPlatform: Platform?,
    onChat: (Platform, String) -> Unit,
    emptyMessage: String,
    emptyContent: @Composable (() -> Unit)?,
    showIcons: Boolean,
) {
    val conversations = remember(chats) { chats?.map { it.conversation } }
    val everyConversation = remember(all) { all?.map { it.conversation } }
    val platformByJid = remember(all) { all?.associate { it.conversation.jid to it.platform }.orEmpty() }
    fun platformOf(jid: String) = platformByJid[jid] ?: fallbackPlatform ?: Platform.SMS

    ChatListContent(
        frame = frame,
        conversations = conversations,
        platformOf = { platformOf(it.jid) },
        onChat = { jid -> onChat(platformOf(jid), jid) },
        emptyMessage = emptyMessage,
        emptyContent = emptyContent,
        icon = if (showIcons) {
            { conv -> platformIcon(platformOf(conv.jid)) }
        } else null,
        reconcileWith = everyConversation,
    )
}

/**
 * [platforms]' chats, merged ([mergeChats]). A platform whose session isn't
 * running yet lists nothing; its session appearing recomposes this onto the
 * real list.
 */
@Composable
private fun rememberChats(platforms: List<Platform>, backend: (Platform) -> ChatBackend?): List<ListedChat>? {
    val lists = platforms.map { platform ->
        platform to (backend(platform)?.conversations ?: notRunning).collectAsState().value
    }
    return remember(lists) { mergeChats(lists) }
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

/** A platform's icon, as All Chats and Archived mark each row. */
@Composable
internal fun platformIcon(platform: Platform): Painter = painterResource(
    when (platform) {
        Platform.WHATSAPP -> R.drawable.ic_whatsapp
        Platform.SIGNAL -> R.drawable.ic_signal
        Platform.SMS -> R.drawable.ic_sms
    },
)

// Stable, so a list whose session hasn't started doesn't resubscribe.
private val notRunning: StateFlow<List<Conversation>?> = MutableStateFlow(null)
