package app.photon.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.photon.chat.ChatBackend
import app.photon.chat.FakeChatBackend
import app.photon.data.model.Message
import app.photon.data.model.MessageStatus
import app.photon.data.model.Platform
import app.photon.ui.RegisterComposeHostActivity
import app.photon.ui.theme.PhotonTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/** The one chat screen, over fake backends with each platform's capabilities. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h413dp-xxhdpi")
class ChatScreenTest {
    private val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(RegisterComposeHostActivity()).around(rule)

    private fun message(id: String, fromMe: Boolean = false) = Message(
        id = id, conversationJid = JID, senderJid = if (fromMe) "me" else JID, timestamp = 1_700_000_000,
        contentType = "text", textBody = "body $id", mediaUrl = null, mediaMime = null, mediaSize = null,
        thumbnailPath = null, stickerPackId = null, replyToId = null, editVersion = 0, isFromMe = fromMe,
        status = if (fromMe) MessageStatus.SENT else MessageStatus.UNREAD,
    )

    private val contacts = mutableListOf<Pair<String, String>>()

    private fun show(fake: FakeChatBackend, backend: ChatBackend = fake) {
        fake.conversations.value = listOf(FakeChatBackend.conversation(JID, "Mum"))
        rule.setContent {
            PhotonTheme {
                ChatScreen(backend, JID, onContact = { phone, name -> contacts += phone to name }, onBack = {})
            }
        }
        // The input appears once the compose settings have loaded.
        rule.waitUntil(5_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aTextOnlyPlatformOffersNeitherAttachNorVoiceNotes() {
        show(FakeChatBackend(Platform.SMS, FakeChatBackend.TEXT_ONLY, phone = JID))
        rule.onAllNodesWithContentDescription("Attach").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Voice note").assertCountEquals(0)
    }

    @Test
    fun aFullPlatformOffersBoth() {
        show(FakeChatBackend(Platform.WHATSAPP, FakeChatBackend.FULL, phone = JID))
        rule.onAllNodesWithContentDescription("Attach").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Voice note").assertCountEquals(1)
    }

    @Test
    fun theTitleOpensTheContactEditorWithTheChatsNumber() {
        val backend = FakeChatBackend(Platform.SIGNAL, FakeChatBackend.FULL, phone = "+447700900123")
        show(backend)
        rule.onNodeWithText("Mum").performClick()
        assertEquals(listOf("+447700900123" to "Mum"), contacts)
        assertEquals(listOf(JID to false), backend.opened)
    }

    @Test
    fun theHeaderHookShowsUnderTheTitle() {
        val fake = FakeChatBackend(Platform.SIGNAL, FakeChatBackend.FULL, phone = null)
        show(fake, fake.withHeader("Safety number changed: Mum"))
        rule.onNodeWithText("Safety number changed: Mum").assertExists()
    }

    @Test
    fun marksReadOncePerNewestIncomingMessage() {
        val backend = FakeChatBackend(Platform.SMS, FakeChatBackend.TEXT_ONLY, phone = JID)
        show(backend)
        backend.messages.value = listOf(message("2"), message("1"))
        rule.waitForIdle()
        // Something else in the thread changes: nothing new to mark.
        backend.messages.value = listOf(message("3", fromMe = true), message("2"), message("1"))
        rule.waitForIdle()
        backend.messages.value = listOf(message("4"), message("3", fromMe = true), message("2"), message("1"))
        rule.waitForIdle()
        assertEquals(listOf(listOf("2", "1"), listOf("4", "2", "1")), backend.marked)
    }

    private companion object {
        const val JID = "+447700900123"
    }
}
