package app.photon.ui.shared

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.photon.data.ComposeStyle
import app.photon.data.KeyboardStyle
import app.photon.data.model.Message
import app.photon.data.model.Platform
import app.photon.ui.RegisterComposeHostActivity
import app.photon.ui.preview.PreviewSamples
import app.photon.ui.shared.components.DELETE_FOR_EVERYONE_WARNING
import app.photon.ui.theme.PhotonTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A long-pressed message's action pages, in the thread at the LP3's size. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h413dp-xxhdpi")
class MessageActionsFlowTest {
    private val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(RegisterComposeHostActivity()).around(rule)

    private val reacted = mutableListOf<Triple<String, String, String>>()
    private val deleted = mutableListOf<Pair<String, Boolean>>()
    private val retried = mutableListOf<String>()

    private val incoming = "Running ten minutes late, sorry!"
    private val ownSent = "No rush, I'm at the bar already"
    private val ownFailed = "Want me to order for you?"

    /** The DM sample; [full] gives it WhatsApp's and Signal's capabilities, otherwise SMS's. */
    private fun showDm(full: Boolean = true, style: ComposeStyle = ComposeStyle.INLINE) {
        rule.setContent {
            PhotonTheme {
                ChatThread(
                    title = PreviewSamples.LONG_DM_TITLE,
                    platform = if (full) Platform.WHATSAPP else Platform.SMS,
                    messages = PreviewSamples.dmMessages,
                    isGroup = false,
                    participantNames = emptyMap(),
                    onSendText = { _, _ -> },
                    onSendAudio = null,
                    onSendMedia = null,
                    onBack = {},
                    onMediaTap = null,
                    onMessagesLoaded = null,
                    onReact = if (full) { id, sender, emoji -> reacted += Triple(id, sender, emoji) } else null,
                    onRetry = { retried += it },
                    onDelete = if (full) { msg: Message, forEveryone -> deleted += msg.id to forEveryone } else null,
                    canDeleteForEveryone = full,
                    onTitleClick = null,
                    supportsReply = full,
                    headerContent = null,
                    composeStyle = style,
                    keyboardStyle = KeyboardStyle.SYSTEM,
                )
            }
        }
    }

    private fun longPress(text: String) {
        rule.onNodeWithText(text).performTouchInput { longClick() }
        rule.waitForIdle()
    }

    private fun shown(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun menuOpen() = shown("Delete") || shown("Retry")

    @Test
    fun anIncomingMessageOffersReplyReactAndDelete() {
        showDm()
        longPress(incoming)
        listOf("Reply", "React", "Delete").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onAllNodesWithText("Delete for Everyone").assertCountEquals(0)
        rule.onAllNodesWithText("Retry").assertCountEquals(0)
        // The top bar names the message (the thread is still beneath); Back sits top-left.
        rule.onAllNodesWithText(incoming).assertCountEquals(2)
        rule.onAllNodesWithContentDescription("Back").assertCountEquals(2)
    }

    @Test
    fun reactOpensAPickerAndSendsTheEmoji() {
        showDm()
        longPress(incoming)
        rule.onNodeWithText("React").performClick()
        rule.onAllNodesWithText("Reply").assertCountEquals(0)
        rule.onNodeWithText("👍").performClick()
        val target = PreviewSamples.dmMessages.first { it.textBody == incoming }
        assertEquals(listOf(Triple(target.id, target.senderJid, "👍")), reacted)
        assertTrue(!shown("👍") && !menuOpen(), "back in the thread")
    }

    @Test
    fun yourOwnMessageOffersNoReaction() {
        showDm()
        longPress(ownSent)
        rule.onAllNodesWithText("React").assertCountEquals(0)
        listOf("Reply", "Delete", "Delete for Everyone").forEach { rule.onNodeWithText(it).assertExists() }
    }

    @Test
    fun deleteForEveryoneSaysWhatHappensBeforeDeleting() {
        showDm()
        longPress(ownSent)
        rule.onNodeWithText("Delete for Everyone").performClick()
        rule.onNodeWithText(DELETE_FOR_EVERYONE_WARNING).assertExists()
        assertEquals(emptyList(), deleted, "nothing deleted before confirming")
        rule.onNodeWithText("DELETE").performClick()
        val target = PreviewSamples.dmMessages.first { it.textBody == ownSent }
        assertEquals(listOf(target.id to true), deleted)
        assertTrue(!shown(DELETE_FOR_EVERYONE_WARNING) && !menuOpen(), "back in the thread")
    }

    @Test
    fun backFromAnyPageReturnsToTheThreadWithoutActing() {
        showDm()
        longPress(ownSent)
        rule.onNodeWithText("Delete for Everyone").performClick()
        // The page's Back, over the thread's.
        rule.onAllNodesWithContentDescription("Back").onLast().performClick()
        assertTrue(!shown(DELETE_FOR_EVERYONE_WARNING) && !menuOpen(), "back in the thread")

        longPress(incoming)
        rule.onNodeWithText("React").performClick()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertTrue(!shown("👍") && !menuOpen(), "system Back returns to the thread too")
        assertEquals(emptyList(), deleted)
        assertEquals(emptyList(), reacted)
    }

    @Test
    fun deleteRemovesTheMessageHereAtOnce() {
        showDm()
        longPress(incoming)
        rule.onNodeWithText("Delete").performClick()
        val target = PreviewSamples.dmMessages.first { it.textBody == incoming }
        assertEquals(listOf(target.id to false), deleted)
    }

    @Test
    fun aFailedSendOffersRetry() {
        showDm()
        longPress(ownFailed)
        rule.onAllNodesWithText("Reply").assertCountEquals(0)
        rule.onNodeWithText("Delete").assertExists()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(listOf(PreviewSamples.dmMessages.first { it.textBody == ownFailed }.id), retried)
    }

    @Test
    fun replyIsTheSwipesReply() {
        showDm()
        longPress(incoming)
        rule.onNodeWithText("Reply").performClick()
        rule.onNodeWithContentDescription("Cancel reply").assertExists()
    }

    @Test
    fun replyOpensTheEditorInFullScreenCompose() {
        showDm(style = ComposeStyle.FULL_SCREEN)
        longPress(incoming)
        rule.onNodeWithText("Reply").performClick()
        // The editor is open, quoting the message in its top bar.
        rule.onAllNodesWithContentDescription("Send").assertCountEquals(1)
        assertTrue(rule.onAllNodesWithText("↳ $incoming", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aMessageWithNothingToOfferHasNoMenu() {
        showDm(full = false)
        longPress(incoming)
        assertTrue(!menuOpen(), "SMS can't reply, react or delete")
        rule.onNode(hasSetTextAction()).assertExists()
    }
}
