package app.photon.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import app.photon.R
import app.photon.data.ComposeStyle
import app.photon.data.KeyboardStyle
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.Platform
import app.photon.data.model.Reaction
import app.photon.ui.shared.ChatListLayout
import app.photon.ui.shared.ChatThread
import app.photon.ui.shared.components.ComposeEditor
import app.photon.ui.shared.components.quoteLabel
import app.photon.ui.theme.PhotonTheme
import com.thelightphone.sdk.ui.LightThemeTokens

// LP3-sized design previews (1080x1240 at 3x) with sample data covering the
// cases the row design has to handle: bursts, a call, a failed send,
// reactions, a reply, a deleted message, a placeholder, and long titles.

private const val LP3_WIDTH_DP = 360
private const val LP3_HEIGHT_DP = 413

/** Sample conversations and threads for previews and layout tests. */
internal object PreviewSamples {
    private val now = System.currentTimeMillis() / 1000
    private const val MIN = 60L
    private const val HOUR = 60 * MIN

    const val LONG_DM_TITLE = "Alexandra Konstantinopoulou-Whitfield"
    const val LONG_GROUP_TITLE = "Saturday climbing crew + partners & kids"

    private fun conversation(
        jid: String,
        name: String,
        platform: Platform,
        ago: Long,
        unread: Int = 0,
        isGroup: Boolean = false,
    ) = Conversation(
        jid = jid, name = name, isGroup = isGroup, lastMessageId = null,
        lastTimestamp = now - ago, unreadCount = unread, isMuted = false,
        avatarUrl = null, updatedAt = now - ago,
    ) to platform

    private val tagged = listOf(
        conversation("a@s.whatsapp.net", LONG_DM_TITLE, Platform.WHATSAPP, 4 * MIN, unread = 2),
        conversation("g@g.us", LONG_GROUP_TITLE, Platform.WHATSAPP, 25 * MIN, unread = 14, isGroup = true),
        conversation("+447700900123", "Mum", Platform.SMS, 2 * HOUR),
        conversation("sig-1", "Sam Okafor", Platform.SIGNAL, 26 * HOUR),
        conversation("HSBC UK", "HSBC UK", Platform.SMS, 50 * HOUR),
        conversation("sig-2", "Book club", Platform.SIGNAL, 4 * 24 * HOUR, isGroup = true),
        conversation("b@s.whatsapp.net", "Priya", Platform.WHATSAPP, 9 * 24 * HOUR),
    )

    val conversations: List<Conversation> = tagged.map { it.first }

    fun platformOf(conversation: Conversation): Platform =
        tagged.first { it.first.jid == conversation.jid }.second

    private fun message(
        id: String,
        ago: Long,
        body: String?,
        fromMe: Boolean = false,
        sender: String = "alexandra@s.whatsapp.net",
        contentType: String = "text",
        status: String = if (fromMe) "read" else "received",
        reactions: List<String> = emptyList(),
        replyTo: Message? = null,
    ) = Message(
        id = id, conversationJid = "chat", senderJid = if (fromMe) "me@s.whatsapp.net" else sender,
        timestamp = now - ago, contentType = contentType, textBody = body, mediaUrl = null,
        mediaMime = null, mediaSize = null, thumbnailPath = null, stickerPackId = null,
        replyToId = replyTo?.id, editVersion = 0, isFromMe = fromMe, status = status,
        reactions = reactions.map { Reaction(id, "x", it, now) },
        replyToPreview = replyTo,
    )

    /** DM thread, newest first. */
    val dmMessages: List<Message> = run {
        val plan = message("d1", 26 * HOUR, "Are we still on for tomorrow night?")
        listOf(
            message("d10", 1 * MIN, "Want me to order for you?", fromMe = true, status = "failed"),
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
            message("g12", 1 * MIN, "Grabbing chalk on the way", fromMe = true, status = "failed"),
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
        title = "All Chats",
        conversations = PreviewSamples.conversations,
        onChat = {},
        onTitleClick = {},
        onSettings = {},
        icon = { conv ->
            painterResource(
                when (PreviewSamples.platformOf(conv)) {
                    Platform.WHATSAPP -> R.drawable.ic_whatsapp
                    Platform.SIGNAL -> R.drawable.ic_signal
                    Platform.SMS -> R.drawable.ic_sms
                },
            )
        },
    )
}

@Composable
internal fun ChatThreadSample(
    title: String,
    messages: List<Message>,
    isGroup: Boolean,
    participantNames: Map<String, String> = emptyMap(),
    composeStyle: ComposeStyle = ComposeStyle.INLINE,
    keyboardStyle: KeyboardStyle = KeyboardStyle.SYSTEM,
) {
    ChatThread(
        title = title,
        messages = messages,
        isGroup = isGroup,
        participantNames = participantNames,
        onSendText = { _, _ -> },
        onSendAudio = { _, _ -> },
        onSendMedia = { _, _, _ -> },
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
        replyLabel = quoteLabel(quoted, quotedSenderName = null, nameOtherSender = false),
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
    ChatListLayout(title = "Signal", conversations = emptyList(), onChat = {}, emptyMessage = "No messages yet", onTitleClick = {}, onSettings = {})
}

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
