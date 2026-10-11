package app.photon.ui.shared

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import app.photon.data.ScrollDial
import app.photon.ui.RegisterComposeHostActivity
import app.photon.ui.preview.PreviewSamples
import app.photon.ui.theme.PhotonTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/** The thread's input at the LP3's 360x413dp viewport. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h413dp-xxhdpi")
class ChatThreadInputTest {
    private val rule = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(RegisterComposeHostActivity()).around(rule)

    private val sent = mutableListOf<Pair<String, String?>>()
    private val retried = mutableListOf<String>()

    private fun showDm() {
        rule.setContent {
            PhotonTheme {
                ChatThread(
                    title = PreviewSamples.LONG_DM_TITLE,
                    messages = PreviewSamples.dmMessages,
                    isGroup = false,
                    participantNames = emptyMap(),
                    onSendText = { text, replyTo -> sent += text to replyTo },
                    onSendAudio = { _, _ -> },
                    onSendMedia = { _, _, _ -> },
                    onBack = {},
                    onMediaTap = null,
                    onMessagesLoaded = null,
                    onReact = { _, _, _ -> },
                    onRetry = { retried += it },
                    onDelete = { _, _ -> },
                    onTitleClick = null,
                    supportsReply = true,
                    headerContent = null,
                )
            }
        }
    }

    private fun field() = rule.onNode(hasSetTextAction())

    private fun focusField() {
        field().performClick()
        field().assertIsFocused()
    }

    private fun message(text: String) = rule.onNodeWithText(text)

    // ─── Inline input ───────────────────────────────────────────

    @Test
    fun inlineBarHasNoKeyboardToggle() {
        showDm()
        rule.onAllNodesWithText("⌨").assertCountEquals(0)
    }

    @Test
    fun tappingTheThreadPutsTheKeyboardAway() {
        showDm()
        focusField()
        message("No rush, I'm at the bar already").performClick()
        field().assertIsNotFocused()
    }

    @Test
    fun tapsOnMessagesStillWorkWhileDismissing() {
        showDm()
        focusField()
        rule.onNodeWithText("failed · tap to retry").performClick()
        assertEquals(listOf("d10"), retried)
        field().assertIsNotFocused()
    }

    @Test
    fun longPressAndSwipeKeepTheKeyboard() {
        showDm()
        focusField()
        message("No rush, I'm at the bar already").performTouchInput { swipeRight() }
        rule.onNodeWithContentDescription("Cancel reply").assertExists()
        field().assertIsFocused()
        message("Running ten minutes late, sorry!").performTouchInput { longClick() }
        field().assertIsFocused()
    }

    @Test
    fun turningTheDialPutsTheKeyboardAway() {
        showDm()
        focusField()
        ScrollDialState.emit(ScrollDialEvent(direction = 1, setting = ScrollDial.MEDIUM))
        rule.waitForIdle()
        field().assertIsNotFocused()
    }

    @Test
    fun sendingKeepsTheKeyboardUp() {
        showDm()
        focusField()
        field().performTextInput("On my way")
        rule.onNodeWithContentDescription("Send").performClick()
        assertEquals(listOf<Pair<String, String?>>("On my way" to null), sent)
        field().assertIsFocused()
    }
}
