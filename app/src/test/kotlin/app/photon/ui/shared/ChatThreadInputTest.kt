package app.photon.ui.shared

import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import app.photon.data.ComposeStyle
import app.photon.data.ScrollDial
import app.photon.ui.RegisterComposeHostActivity
import app.photon.ui.preview.PreviewSamples
import app.photon.ui.theme.PhotonTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The thread's input, inline and full-screen, at the LP3's 360x413dp viewport. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h413dp-xxhdpi")
class ChatThreadInputTest {
    private val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(RegisterComposeHostActivity()).around(rule)

    private val sent = mutableListOf<Pair<String, String?>>()
    private val retried = mutableListOf<String>()

    private fun showDm(style: ComposeStyle = ComposeStyle.INLINE, canSendMedia: Boolean = true) {
        rule.setContent {
            PhotonTheme {
                ChatThread(
                    title = PreviewSamples.LONG_DM_TITLE,
                    messages = PreviewSamples.dmMessages,
                    isGroup = false,
                    participantNames = emptyMap(),
                    onSendText = { text, replyTo -> sent += text to replyTo },
                    onSendAudio = if (canSendMedia) { _, _ -> } else null,
                    onSendMedia = if (canSendMedia) { _, _, _ -> } else null,
                    onBack = {},
                    onMediaTap = null,
                    onMessagesLoaded = null,
                    onReact = { _, _, _ -> },
                    onRetry = { retried += it },
                    onDelete = { _, _ -> },
                    onTitleClick = null,
                    supportsReply = true,
                    headerContent = null,
                    composeStyle = style,
                )
            }
        }
    }

    private val softKeyboardUp: Boolean
        get() {
            rule.waitForIdle()
            return shadowOf(rule.activity.getSystemService(InputMethodManager::class.java)).isSoftInputVisible
        }

    private fun field() = rule.onNode(hasSetTextAction())

    private fun focusField() {
        field().performClick()
        field().assertIsFocused()
        assertTrue(softKeyboardUp)
    }

    private fun message(text: String) = rule.onNodeWithText(text)

    private fun described(description: String) = rule.onNodeWithContentDescription(description)

    private fun SemanticsNodeInteraction.bounds(): Rect =
        getBoundsInRoot().let { Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }

    // ─── Inline ─────────────────────────────────────────────────

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
        assertFalse(softKeyboardUp)
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
        described("Cancel reply").assertExists()
        field().assertIsFocused()
        message("Running ten minutes late, sorry!").performTouchInput { longClick() }
        field().assertIsFocused()
        assertTrue(softKeyboardUp)
    }

    @Test
    fun turningTheDialPutsTheKeyboardAway() {
        showDm()
        focusField()
        ScrollDialState.emit(ScrollDialEvent(direction = 1, setting = ScrollDial.MEDIUM))
        rule.waitForIdle()
        field().assertIsNotFocused()
        assertFalse(softKeyboardUp)
    }

    @Test
    fun sendingKeepsTheKeyboardUp() {
        showDm()
        focusField()
        field().performTextInput("On my way")
        described("Send").performClick()
        assertEquals(listOf<Pair<String, String?>>("On my way" to null), sent)
        field().assertIsFocused()
        assertTrue(softKeyboardUp)
    }

    // ─── Full screen ────────────────────────────────────────────

    /** Types on the editor's embedded LP3 keyboard, one key tap per character. */
    private fun typeOnLp3Keyboard(text: String) {
        text.forEach { char ->
            rule.onNodeWithText(char.toString(), useUnmergedTree = true).performTouchInput { click() }
            rule.waitForIdle()
        }
    }

    private fun editorOpen() = rule.onAllNodesWithContentDescription("Send").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun fullScreenHasABottomBarInsteadOfTheField() {
        showDm(ComposeStyle.FULL_SCREEN)
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        val attach = described("Attach").apply { assertIsDisplayed() }.bounds()
        val mic = described("Voice note").apply { assertIsDisplayed() }.bounds()
        val compose = described("Compose").apply { assertIsDisplayed() }.bounds()
        assertTrue(attach.right <= mic.left && mic.right <= compose.left, "attach $attach, mic $mic, compose $compose")
        assertTrue(compose.bottom > 380f, "bottom bar $compose should sit at the bottom of the 413dp screen")
    }

    @Test
    fun fullScreenWithoutMediaKeepsComposeOnTheRight() {
        showDm(ComposeStyle.FULL_SCREEN, canSendMedia = false)
        rule.onAllNodesWithContentDescription("Attach").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Voice note").assertCountEquals(0)
        assertTrue(described("Compose").bounds().left > 300f)
    }

    @Test
    fun composeOpensTheEditorWithoutTheSystemKeyboard() {
        showDm(ComposeStyle.FULL_SCREEN)
        described("Compose").performClick()
        assertTrue(editorOpen())
        // The editor draws its own LP3 keyboard: no text field, no IME.
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertFalse(softKeyboardUp)
    }

    @Test
    fun replyingOpensComposeAndSendingSendsTheReply() {
        showDm(ComposeStyle.FULL_SCREEN)
        message("Running ten minutes late, sorry!").performTouchInput { swipeRight() }
        assertTrue(editorOpen())
        // The quote sits under the title in the editor's top bar (the
        // thread's reply chip is behind the editor).
        val quote = rule.onAllNodesWithText(
            "↳ Running ten minutes late, sorry!",
            useUnmergedTree = true,
        ).onLast().bounds()
        assertTrue(quote.bottom <= 45f, "quote $quote should be in the top bar")

        typeOnLp3Keyboard("Ok")
        described("Send").performClick()

        assertEquals(listOf<Pair<String, String?>>("Ok" to "d8"), sent)
        assertFalse(editorOpen())
        rule.onAllNodesWithContentDescription("Cancel reply").assertCountEquals(0)
    }

    @Test
    fun blankDraftsDoNotSend() {
        showDm(ComposeStyle.FULL_SCREEN)
        described("Compose").performClick()
        described("Send").performClick()
        assertEquals(emptyList(), sent)
        assertTrue(editorOpen())
    }

    @Test
    fun backKeepsTheDraft() {
        showDm(ComposeStyle.FULL_SCREEN)
        described("Compose").performClick()
        typeOnLp3Keyboard("Hi")

        // System Back (BackHandler) returns to the thread...
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertFalse(editorOpen())
        described("Compose").assertIsDisplayed()

        // ...and the draft is waiting when compose reopens.
        described("Compose").performClick()
        rule.onNodeWithText("Hi", useUnmergedTree = true).assertIsDisplayed()

        // The editor's own Back (drawn over the thread's) does the same.
        rule.onAllNodesWithContentDescription("Back").onLast().performClick()
        assertFalse(editorOpen())
        described("Compose").performClick()
        rule.onNodeWithText("Hi", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(emptyList(), sent)
    }
}
