package app.photon.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.photon.data.ComposeStyle
import app.photon.data.KeyboardStyle
import app.photon.data.model.ContentTypes
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.MessageStatus
import app.photon.data.model.Platform
import app.photon.data.model.Reaction
import app.photon.ui.chat.ARCHIVED
import app.photon.ui.chat.platformIcon
import app.photon.ui.home.PlatformsScreen
import app.photon.ui.shared.ChatListFrame
import app.photon.ui.shared.ChatListLayout
import app.photon.ui.shared.ChatThread
import app.photon.ui.shared.components.ComposeEditor
import app.photon.ui.shared.components.quoteLabel
import app.photon.ui.theme.PhotonTheme
import com.thelightphone.sdk.ui.LightThemeTokens

// LP3-sized design previews (1080x1240 at 3x) with sample data covering the
// cases the row design has to handle: bursts, a call, a failed send,
// reactions, a reply, a deleted message, a placeholder, long titles, a
// muted chat with unread messages, and the 0.5.0 rows (system line,
// view-once, unavailable, location) under a disappearing-messages header.

private const val LP3_WIDTH_DP = 360
private const val LP3_HEIGHT_DP = 413

/** Sample conversations and threads for previews and layout tests. */
internal object PreviewSamples {
    private val now = System.currentTimeMillis() / 1000
    private const val MIN = 60L
    private const val HOUR = 60 * MIN

    const val LONG_DM_TITLE = "Alexandra Konstantinopoulou-Whitfield"
    const val LONG_GROUP_TITLE = "Saturday climbing crew + partners & kids"
    const val MUTED_TITLE = "Neighbours"

    private fun conversation(
        jid: String,
        name: String,
        platform: Platform,
        ago: Long,
        unread: Int = 0,
        isGroup: Boolean = false,
        muted: Boolean = false,
        archived: Boolean = false,
    ) = Conversation(
        jid = jid, name = name, isGroup = isGroup, lastMessageId = null,
        lastTimestamp = now - ago, unreadCount = unread, isMuted = muted,
        avatarUrl = null, updatedAt = now - ago, isArchived = archived,
    ) to platform

    private val tagged = listOf(
        conversation("a@s.whatsapp.net", LONG_DM_TITLE, Platform.WHATSAPP, 4 * MIN, unread = 2),
        conversation("g@g.us", LONG_GROUP_TITLE, Platform.WHATSAPP, 25 * MIN, unread = 14, isGroup = true),
        conversation("n@g.us", MUTED_TITLE, Platform.WHATSAPP, 50 * MIN, unread = 3, isGroup = true, muted = true),
        conversation("+447700900123", "Mum", Platform.SMS, 2 * HOUR),
        conversation("sig-1", "Sam Okafor", Platform.SIGNAL, 26 * HOUR),
        conversation("HSBC UK", "HSBC UK", Platform.SMS, 50 * HOUR),
        conversation("sig-2", "Book club", Platform.SIGNAL, 4 * 24 * HOUR, isGroup = true),
        conversation("b@s.whatsapp.net", "Priya", Platform.WHATSAPP, 9 * 24 * HOUR),
    )

    /** Chats archived on the phone, one of them with a new message. */
    private val taggedArchived = listOf(
        conversation("sig-3", "Marcus Lee", Platform.SIGNAL, 3 * HOUR, unread = 1, archived = true),
        conversation("f@g.us", "Old flatmates", Platform.WHATSAPP, 3 * 24 * HOUR, isGroup = true, archived = true),
        conversation("Dentist", "Dentist", Platform.SMS, 12 * 24 * HOUR, archived = true),
        conversation("p@g.us", "Parents' evening", Platform.WHATSAPP, 40 * 24 * HOUR, isGroup = true, muted = true, archived = true),
    )

    val conversations: List<Conversation> = tagged.map { it.first }
    val archivedConversations: List<Conversation> = taggedArchived.map { it.first }

    fun platformOf(conversation: Conversation): Platform =
        (tagged + taggedArchived).first { it.first.jid == conversation.jid }.second

    private fun message(
        id: String,
        ago: Long,
        body: String?,
        fromMe: Boolean = false,
        sender: String = "alexandra@s.whatsapp.net",
        contentType: String = "text",
        status: MessageStatus = if (fromMe) MessageStatus.READ else MessageStatus.UNREAD,
        reactions: List<String> = emptyList(),
        replyTo: Message? = null,
        mediaUrl: String? = null,
    ) = Message(
        id = id, conversationJid = "chat", senderJid = if (fromMe) "me@s.whatsapp.net" else sender,
        timestamp = now - ago, contentType = contentType, textBody = body, mediaUrl = mediaUrl,
        mediaMime = null, mediaSize = null, thumbnailPath = null, stickerPackId = null,
        replyToId = replyTo?.id, editVersion = 0, isFromMe = fromMe, status = status,
        reactions = reactions.map { Reaction(id, "x", it, now) },
        replyToPreview = replyTo,
    )

    /** DM thread, newest first. */
    val dmMessages: List<Message> = run {
        val plan = message("d1", 26 * HOUR, "Are we still on for tomorrow night?")
        listOf(
            message("d10", 1 * MIN, "Want me to order for you?", fromMe = true, status = MessageStatus.FAILED),
            message("d9", 3 * MIN, "No rush, I'm at the bar already", fromMe = true),
            message("d8", 4 * MIN, "Running ten minutes late, sorry!"),
            message("d7", 20 * MIN, "Missed voice call", contentType = "call"),
            message("d6", 45 * MIN, "Table for four at 8 — booked", reactions = listOf("❤️", "👍")),
            message("d5", 46 * MIN, null),
            message("d4", 47 * MIN, "Yes! Booking now", replyTo = plan),
            message("d3", 25 * HOUR, "Sounds good, see you there", fromMe = true),
            message("d2", 25 * HOUR + 2 * MIN, "8pm at the usual place?"),
            plan,
        )
    }

    const val NEW_ROWS_TITLE = "Sam Okafor"
    const val SYSTEM_LINE = "Disappearing messages · 1 week"
    const val LOCATION_NAME = "Buckingham Palace"
    const val LOCATION_GEO = "geo:51.501364,-0.14189"

    /** A week, the disappearing-messages timer of [newRowMessages]' chat. */
    const val WEEK_SECONDS = 7 * 24 * 3600L

    /**
     * A DM (Signal, timer on) with the 0.5.0 rows: a system line, a
     * view-once photo, a message still on its way, and a location. Newest first.
     */
    val newRowMessages: List<Message> = listOf(
        message("n7", 2 * MIN, LOCATION_NAME, contentType = ContentTypes.LOCATION, mediaUrl = LOCATION_GEO),
        message("n6", 3 * MIN, "Meet you by the gates"),
        message("n5", 9 * MIN, null, contentType = ContentTypes.UNAVAILABLE),
        message("n4", 30 * MIN, "photo", contentType = ContentTypes.VIEW_ONCE),
        message("n3", 58 * MIN, "Done, a week it is", fromMe = true),
        message("n2", 59 * MIN, SYSTEM_LINE, contentType = ContentTypes.SYSTEM, status = MessageStatus.READ),
        message("n1", 62 * MIN, "Can you turn on disappearing messages?"),
    )

    private const val ALICE = "alice@s.whatsapp.net"
    private const val BOB = "bob@s.whatsapp.net"
    private const val MAX = "max@s.whatsapp.net"

    val groupNames = mapOf(
        ALICE to "Alice",
        BOB to "Bob",
        MAX to "Maximilian Featherstonehaugh",
    )

    /** Group thread, newest first. */
    val groupMessages: List<Message> = run {
        val question = message("g2", 70 * MIN, "Who's driving on Saturday?", sender = ALICE)
        listOf(
            message("g12", 1 * MIN, "Grabbing chalk on the way", fromMe = true, status = MessageStatus.FAILED),
            message("g11", 3 * MIN, "[ unsupported message ]", sender = BOB),
            message("g10", 4 * MIN, "Perfect", sender = BOB, reactions = listOf("👍", "👍", "🙏")),
            message("g9", 12 * MIN, "Missed group video call", contentType = "call"),
            message("g8", 30 * MIN, "Kids' harnesses are in the boot already", sender = MAX),
            message("g7", 31 * MIN, "I can take three in the van, leaving at 9", sender = MAX),
            message("g6", 40 * MIN, "I'll drive", fromMe = true, replyTo = question),
            message("g5", 41 * MIN, "Count me in", fromMe = true),
            message("g4", 60 * MIN, "Me too, if there's space", sender = BOB),
            message("g3", 66 * MIN, "I'm in!", sender = BOB),
            question,
            message("g1", 72 * MIN, "Morning all", sender = ALICE),
        )
    }
}

@Composable
private fun PhotonPreview(content: @Composable () -> Unit) {
    PhotonTheme {
        Box(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) { content() }
    }
}

@Composable
internal fun ChatListSample() {
    ChatListLayout(
        frame = ChatListFrame.Main("All Chats", onFilter = {}, onSettings = {}),
        conversations = PreviewSamples.conversations,
        platformOf = PreviewSamples::platformOf,
        onChat = {},
        icon = { conv -> platformIcon(PreviewSamples.platformOf(conv)) },
    )
}

/** Archived, opened from the platform picker. */
@Composable
internal fun ArchivedChatsSample() {
    ChatListLayout(
        frame = ChatListFrame.Page(ARCHIVED, onBack = {}),
        conversations = PreviewSamples.archivedConversations,
        platformOf = PreviewSamples::platformOf,
        onChat = {},
        icon = { conv -> platformIcon(PreviewSamples.platformOf(conv)) },
    )
}

@Composable
internal fun ChatThreadSample(
    title: String,
    messages: List<Message>,
    isGroup: Boolean,
    platform: Platform = Platform.WHATSAPP,
    participantNames: Map<String, String> = emptyMap(),
    composeStyle: ComposeStyle = ComposeStyle.INLINE,
    keyboardStyle: KeyboardStyle = KeyboardStyle.SYSTEM,
    disappearingSeconds: Long = 0,
) {
    ChatThread(
        title = title,
        platform = platform,
        messages = messages,
        isGroup = isGroup,
        participantNames = participantNames,
        onSendText = { _, _ -> },
        onSendAudio = { _, _ -> },
        onSendMedia = { _, _, _, _ -> },
        onBack = {},
        onMediaTap = null,
        onMessagesLoaded = null,
        onReact = { _, _, _ -> },
        onRetry = {},
        onDelete = { _, _ -> },
        onTitleClick = {},
        supportsReply = true,
        headerContent = null,
        composeStyle = composeStyle,
        keyboardStyle = keyboardStyle,
        disappearingSeconds = disappearingSeconds,
    )
}

/** The 0.5.0 rows in a DM whose messages disappear after a week. */
@Composable
internal fun NewRowsThreadSample() {
    ChatThreadSample(
        title = PreviewSamples.NEW_ROWS_TITLE,
        messages = PreviewSamples.newRowMessages,
        isGroup = false,
        platform = Platform.SIGNAL,
        disappearingSeconds = PreviewSamples.WEEK_SECONDS,
    )
}

@Composable
internal fun DmThreadSample(
    composeStyle: ComposeStyle = ComposeStyle.INLINE,
    keyboardStyle: KeyboardStyle = KeyboardStyle.SYSTEM,
) {
    ChatThreadSample(
        PreviewSamples.LONG_DM_TITLE, PreviewSamples.dmMessages, isGroup = false,
        composeStyle = composeStyle, keyboardStyle = keyboardStyle,
    )
}

/** FULL SCREEN compose, replying to a message, with a draft under way. */
@Composable
internal fun ComposeEditorSample(keyboard: KeyboardStyle = KeyboardStyle.LIGHT) {
    val quoted = PreviewSamples.dmMessages.first { it.id == "d8" }
    ComposeEditor(
        title = PreviewSamples.LONG_DM_TITLE,
        draft = rememberTextFieldState("No problem, see you soon"),
        keyboard = keyboard,
        replyLabel = quoteLabel(quoted, quotedSenderName = null),
        onSend = {},
        onBack = {},
    )
}

@Composable
internal fun GroupThreadSample() {
    ChatThreadSample(
        title = PreviewSamples.LONG_GROUP_TITLE,
        messages = PreviewSamples.groupMessages,
        isGroup = true,
        participantNames = PreviewSamples.groupNames,
    )
}

@Preview(name = "Chat list", widthDp = LP3_WIDTH_DP, heightDp = LP3_HEIGHT_DP, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ChatListPreview() = PhotonPreview { ChatListSample() }

@Preview(name = "Chat list – empty", widthDp = LP3_WIDTH_DP, heightDp = LP3_HEIGHT_DP, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun EmptyChatListPreview() = PhotonPreview {
    ChatListLayout(
        frame = ChatListFrame.Main("Signal", onFilter = {}, onSettings = {}),
        conversations = emptyList(),
        platformOf = { Platform.SIGNAL },
        onChat = {},
        emptyMessage = "no messages yet",
    )
}

@Preview(name = "Archived", widthDp = LP3_WIDTH_DP, heightDp = LP3_HEIGHT_DP, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ArchivedChatsPreview() = PhotonPreview { ArchivedChatsSample() }

@Preview(name = "Platform picker", widthDp = LP3_WIDTH_DP, heightDp = LP3_HEIGHT_DP, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PlatformsPreview() = PhotonPreview { PlatformsScreen(onPick = {}, onArchived = {}, onBack = {}) }

@Preview(name = "DM", widthDp = LP3_WIDTH_DP, heightDp = LP3_HEIGHT_DP, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun DmChatPreview() = PhotonPreview { DmThreadSample() }

@Preview(name = "DM – full-screen compose", widthDp = LP3_WIDTH_DP, heightDp = LP3_HEIGHT_DP, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun DmFullScreenPreview() = PhotonPreview { DmThreadSample(ComposeStyle.FULL_SCREEN) }

@Preview(name = "Compose (full screen) – LP3 keyboard", widthDp = LP3_WIDTH_DP, heightDp = LP3_HEIGHT_DP, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ComposeEditorPreview() = PhotonPreview { ComposeEditorSample(KeyboardStyle.LIGHT) }

@Preview(name = "Compose (full screen) – system keyboard", widthDp = LP3_WIDTH_DP, heightDp = LP3_HEIGHT_DP, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun SystemComposeEditorPreview() = PhotonPreview { ComposeEditorSample(KeyboardStyle.SYSTEM) }

@Preview(name = "Group", widthDp = LP3_WIDTH_DP, heightDp = LP3_HEIGHT_DP, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun GroupChatPreview() = PhotonPreview { GroupThreadSample() }

@Preview(name = "DM – new rows, disappearing", widthDp = LP3_WIDTH_DP, heightDp = LP3_HEIGHT_DP, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun NewRowsPreview() = PhotonPreview { NewRowsThreadSample() }
