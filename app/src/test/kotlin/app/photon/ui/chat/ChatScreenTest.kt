package app.photon.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.photon.chat.ChatBackend
import app.photon.chat.ChatBackendException
import app.photon.chat.ChatCapabilities
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.MessageStatus
import app.photon.data.model.Platform
import app.photon.ui.RegisterComposeHostActivity
import app.photon.ui.theme.PhotonTheme
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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

    private val full = ChatCapabilities(
        reply = true, media = true, audio = true, reactions = true,
        delete = true, deleteForEveryone = true, contactEditing = true,
    )
    private val textOnly = full.copy(reply = false, media = false, audio = false, reactions = false, delete = false, deleteForEveryone = false)

    private fun message(id: String, fromMe: Boolean = false) = Message(
        id = id, conversationJid = JID, senderJid = if (fromMe) "me" else JID, timestamp = 1_700_000_000,
        contentType = "text", textBody = "body $id", mediaUrl = null, mediaMime = null, mediaSize = null,
        thumbnailPath = null, stickerPackId = null, replyToId = null, editVersion = 0, isFromMe = fromMe,
        status = if (fromMe) MessageStatus.SENT else MessageStatus.UNREAD,
    )

    private class FakeBackend(
        override val platform: Platform,
        override val capabilities: ChatCapabilities,
        private val phone: String?,
        private val header: String? = null,
    ) : ChatBackend {
        val messages = MutableStateFlow<List<Message>>(emptyList())
        val marked = mutableListOf<List<String>>()
        val opened = mutableListOf<Pair<String, Boolean>>()

        override val conversations = MutableStateFlow<List<Conversation>?>(
            listOf(Conversation(JID, "Mum", isGroup = false, null, 0, 0, false, null, 0)),
        )

        override fun conversation(jid: String) = conversations.value?.firstOrNull { it.jid == jid }
        override fun messages(jid: String): Flow<List<Message>> = messages
        override fun participantNames(jid: String): Flow<Map<String, String>> = flowOf(emptyMap())
        override suspend fun sendText(jid: String, text: String, replyToId: String?) {}
        override suspend fun sendMedia(jid: String, path: String, mime: String, replyToId: String?) {}
        override suspend fun react(jid: String, messageId: String, senderJid: String, emoji: String) {}
        override suspend fun retry(messageId: String) {}
        override suspend fun delete(jid: String, messageId: String, forEveryone: Boolean) {}

        override suspend fun markRead(jid: String, incomingIds: List<String>) {
            marked += incomingIds
        }

        override suspend fun downloadMedia(messageId: String): String = throw ChatBackendException("none")

        override suspend fun onOpen(jid: String, isGroup: Boolean) {
            opened += jid to isGroup
        }

        @Composable
        override fun Header(jid: String, isGroup: Boolean, title: String, participantNames: Map<String, String>) {
            header?.let { LightText(it, variant = LightTextVariant.Detail) }
        }

        override fun contactPhone(jid: String) = phone
    }

    private val contacts = mutableListOf<Pair<String, String>>()

    private fun show(backend: FakeBackend) {
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
        show(FakeBackend(Platform.SMS, textOnly, phone = JID))
        rule.onAllNodesWithContentDescription("Attach").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Voice note").assertCountEquals(0)
    }

    @Test
    fun aFullPlatformOffersBoth() {
        show(FakeBackend(Platform.WHATSAPP, full, phone = JID))
        rule.onAllNodesWithContentDescription("Attach").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Voice note").assertCountEquals(1)
    }

    @Test
    fun theTitleOpensTheContactEditorWithTheChatsNumber() {
        val backend = FakeBackend(Platform.SIGNAL, full, phone = "+447700900123")
        show(backend)
        rule.onNodeWithText("Mum").performClick()
        assertEquals(listOf("+447700900123" to "Mum"), contacts)
        assertEquals(listOf(JID to false), backend.opened)
    }

    @Test
    fun theHeaderHookShowsUnderTheTitle() {
        show(FakeBackend(Platform.SIGNAL, full, phone = null, header = "Safety number changed: Mum"))
        rule.onNodeWithText("Safety number changed: Mum").performClick()
    }

    @Test
    fun marksReadOncePerNewestIncomingMessage() {
        val backend = FakeBackend(Platform.SMS, textOnly, phone = JID)
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
