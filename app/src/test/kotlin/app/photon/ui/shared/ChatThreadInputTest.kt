package app.photon.ui.shared

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
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
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import app.photon.data.ComposeStyle
import app.photon.data.KeyboardStyle
import app.photon.data.ScrollDial
import app.photon.data.model.Platform
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
import kotlin.test.assertNull
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertTrue

/**
 * The thread's input in each COMPOSE × KEYBOARD combination, at the LP3's
 * 360x413dp viewport.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h413dp-xxhdpi")
class ChatThreadInputTest {
    private val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(RegisterComposeHostActivity()).around(rule)

    private val sent = mutableListOf<Pair<String, String?>>()
    private val retried = mutableListOf<String>()

    private fun showDm(
        style: ComposeStyle = ComposeStyle.INLINE,
        keyboard: KeyboardStyle = KeyboardStyle.SYSTEM,
        canSendMedia: Boolean = true,
        onBack: () -> Unit = {},
        conversationKey: () -> String? = { null },
    ) {
        rule.setContent {
            PhotonTheme {
                ChatThread(
                    title = PreviewSamples.LONG_DM_TITLE,
                    platform = Platform.WHATSAPP,
                    messages = PreviewSamples.dmMessages,
                    isGroup = false,
                    participantNames = emptyMap(),
                    onSendText = { text, replyTo -> sent += text to replyTo },
                    onSendAudio = if (canSendMedia) { _, _ -> } else null,
                    onSendMedia = if (canSendMedia) { _, _, _, _ -> } else null,
                    onBack = onBack,
                    onMediaTap = null,
                    onMessagesLoaded = null,
                    onReact = { _, _, _ -> },
                    onRetry = { retried += it },
                    onDelete = { _, _ -> },
                    onTitleClick = null,
                    supportsReply = true,
                    headerContent = null,
                    composeStyle = style,
                    keyboardStyle = keyboard,
                    conversationKey = conversationKey(),
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

    /** System Back, through the activity's dispatcher (BackHandlers). */
    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    /** Types on the LP3 keyboard, one key tap per character. */
    private fun typeOnLp3Keyboard(text: String) {
        text.forEach { char ->
            rule.onNodeWithText(char.toString(), useUnmergedTree = true).performTouchInput { click() }
            rule.waitForIdle()
        }
    }

    private fun lp3KeyboardShown() =
        rule.onAllNodesWithText("Z", ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun editorOpen() = rule.onAllNodesWithContentDescription("Send").fetchSemanticsNodes().isNotEmpty()

    private fun fieldText() = field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /** The view Compose draws into, which is what an IME would connect to. */
    private fun composeHost(): View {
        fun find(view: View): View? = when {
            view.javaClass.simpleName == "AndroidComposeView" -> view
            view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        return checkNotNull(find(rule.activity.window.decorView))
    }

    private fun described(description: String) = rule.onNodeWithContentDescription(description)

    private fun SemanticsNodeInteraction.bounds(): Rect =
        getBoundsInRoot().let { Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }

    // ─── Inline + system keyboard ───────────────────────────────

    /** A notification can swap the chat shown in place: its draft mustn't follow. */
    @Test
    fun eachConversationKeepsItsOwnDraft() {
        var key by mutableStateOf("alice@s.whatsapp.net")
        showDm(conversationKey = { key })
        field().performTextInput("For Alice")
        assertEquals("For Alice", fieldText())

        rule.runOnIdle { key = "bob@s.whatsapp.net" }
        assertEquals("", fieldText())
    }

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
    fun swipingToReplyKeepsTheKeyboard() {
        showDm()
        focusField()
        message("No rush, I'm at the bar already").performTouchInput { swipeRight() }
        described("Cancel reply").assertExists()
        field().assertIsFocused()
        assertTrue(softKeyboardUp)
    }

    /** Long-press opens a full-screen page of actions, which no keyboard covers. */
    @Test
    fun longPressPutsTheKeyboardAwayForTheActions() {
        showDm()
        focusField()
        message("Running ten minutes late, sorry!").performTouchInput { longClick() }
        rule.onNodeWithText("React").assertExists()
        field().assertIsNotFocused()
        assertFalse(softKeyboardUp)
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

    // ─── Full screen + LP3 keyboard ─────────────────────────────

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

    /** The pixels of the icon described as [description], as the screen draws them. */
    private fun iconPixels(description: String): IntArray {
        rule.waitForIdle()
        val r = described(description).fetchSemanticsNode().boundsInWindow
        val view = rule.activity.window.decorView
        val screen = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(screen))
        val w = r.width.roundToInt()
        val h = r.height.roundToInt()
        return IntArray(w * h).also { screen.getPixels(it, 0, w, r.left.roundToInt(), r.top.roundToInt(), w, h) }
    }

    /** Voice note is one icon in both compose styles, so they can't drift apart. */
    @Test
    fun voiceNoteLooksTheSameInBothComposeStyles() {
        var style by mutableStateOf(ComposeStyle.INLINE)
        rule.setContent {
            PhotonTheme {
                ChatThread(
                    title = PreviewSamples.LONG_DM_TITLE, platform = Platform.WHATSAPP, messages = PreviewSamples.dmMessages,
                    isGroup = false, participantNames = emptyMap(), onSendText = { _, _ -> }, onSendAudio = { _, _ -> },
                    onSendMedia = { _, _, _, _ -> }, onBack = {}, onMediaTap = null, onMessagesLoaded = null, onReact = null,
                    onRetry = null, onDelete = null, onTitleClick = null, supportsReply = true, headerContent = null,
                    composeStyle = style, keyboardStyle = KeyboardStyle.SYSTEM,
                )
            }
        }
        val inline = iconPixels("Voice note")
        style = ComposeStyle.FULL_SCREEN
        val fullScreen = iconPixels("Voice note")
        assertEquals(inline.size, fullScreen.size, "same size")
        // Allow for anti-aliasing at a different sub-pixel offset; another icon differs far more.
        val differing = inline.indices.count { abs(Color.red(inline[it]) - Color.red(fullScreen[it])) > 96 }
        assertTrue(differing < inline.size / 20, "$differing of ${inline.size} pixels differ")
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
        showDm(ComposeStyle.FULL_SCREEN, KeyboardStyle.LIGHT)
        described("Compose").performClick()
        assertTrue(editorOpen())
        // The editor draws its own LP3 keyboard: no text field, no IME.
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertFalse(softKeyboardUp)
    }

    @Test
    fun replyingOpensComposeAndSendingSendsTheReply() {
        showDm(ComposeStyle.FULL_SCREEN, KeyboardStyle.LIGHT)
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
        showDm(ComposeStyle.FULL_SCREEN, KeyboardStyle.LIGHT)
        described("Compose").performClick()
        described("Send").performClick()
        assertEquals(emptyList(), sent)
        assertTrue(editorOpen())
    }

    @Test
    fun backKeepsTheDraft() {
        showDm(ComposeStyle.FULL_SCREEN, KeyboardStyle.LIGHT)
        described("Compose").performClick()
        typeOnLp3Keyboard("Hi")

        // System Back (BackHandler) returns to the thread...
        pressBack()
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

    // ─── Inline + LP3 keyboard ──────────────────────────────────

    @Test
    fun lightInlineDocksTheLp3KeyboardAndNeverTheSystemOne() {
        showDm(keyboard = KeyboardStyle.LIGHT)
        assertFalse(lp3KeyboardShown())
        field().performClick()
        field().assertIsFocused()
        assertTrue(lp3KeyboardShown())
        assertFalse(softKeyboardUp)
        // No input connection for an IME to attach to.
        assertFalse(composeHost().onCheckIsTextEditor())
        assertNull(composeHost().onCreateInputConnection(EditorInfo()))
        // Docked under the bar.
        val keyTop = rule.onNodeWithText("Z", ignoreCase = true, useUnmergedTree = true).bounds().top
        assertTrue(field().bounds().bottom <= keyTop, "keyboard should sit under the field")
    }

    @Test
    fun lightInlineKeysEditTheDraftAndSendingKeepsTheKeyboard() {
        showDm(keyboard = KeyboardStyle.LIGHT)
        field().performClick()
        typeOnLp3Keyboard("Hi")
        assertEquals("Hi", fieldText())
        described("Send").performClick()
        assertEquals(listOf<Pair<String, String?>>("Hi" to null), sent)
        assertEquals("", fieldText())
        field().assertIsFocused()
        assertTrue(lp3KeyboardShown())
        assertFalse(softKeyboardUp)
    }

    @Test
    fun lightInlineSendsTheReply() {
        showDm(keyboard = KeyboardStyle.LIGHT)
        message("Running ten minutes late, sorry!").performTouchInput { swipeRight() }
        field().performClick()
        typeOnLp3Keyboard("Ok")
        described("Send").performClick()
        assertEquals(listOf<Pair<String, String?>>("Ok" to "d8"), sent)
    }

    @Test
    fun backPutsTheLp3KeyboardAwayBeforeLeaving() {
        var left = false
        showDm(keyboard = KeyboardStyle.LIGHT, onBack = { left = true })
        field().performClick()
        assertTrue(lp3KeyboardShown())
        pressBack()
        assertFalse(lp3KeyboardShown())
        field().assertIsNotFocused()
        assertFalse(left)
    }

    @Test
    fun tappingTheThreadOrTurningTheDialPutsTheLp3KeyboardAway() {
        showDm(keyboard = KeyboardStyle.LIGHT)
        field().performClick()
        assertTrue(lp3KeyboardShown())
        message("No rush, I'm at the bar already").performClick()
        assertFalse(lp3KeyboardShown())
        field().assertIsNotFocused()

        field().performClick()
        assertTrue(lp3KeyboardShown())
        ScrollDialState.emit(ScrollDialEvent(direction = 1, setting = ScrollDial.MEDIUM))
        rule.waitForIdle()
        assertFalse(lp3KeyboardShown())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aHardwareKeyboardTypesIntoTheFieldWithTheLp3KeyboardUp() {
        showDm(keyboard = KeyboardStyle.LIGHT)
        field().performClick()
        field().performKeyInput { pressKey(Key.A) }
        assertEquals("a", fieldText())
        assertTrue(lp3KeyboardShown())
    }

    // ─── Full screen + system keyboard ──────────────────────────

    @Test
    fun systemComposeFocusesTheEditorAndRaisesTheSystemKeyboard() {
        showDm(ComposeStyle.FULL_SCREEN, KeyboardStyle.SYSTEM)
        described("Compose").performClick()
        assertTrue(editorOpen())
        field().assertIsFocused()
        assertTrue(softKeyboardUp)
        assertFalse(lp3KeyboardShown())
    }

    @Test
    fun systemComposeSendsTheReply() {
        showDm(ComposeStyle.FULL_SCREEN, KeyboardStyle.SYSTEM)
        message("Running ten minutes late, sorry!").performTouchInput { swipeRight() }
        assertTrue(editorOpen())
        val quote = rule.onAllNodesWithText("↳ Running ten minutes late, sorry!", useUnmergedTree = true)
            .onLast().bounds()
        assertTrue(quote.bottom <= 45f, "quote $quote should be in the top bar")

        field().performTextInput("Ok")
        described("Send").performClick()

        assertEquals(listOf<Pair<String, String?>>("Ok" to "d8"), sent)
        assertFalse(editorOpen())
        assertFalse(softKeyboardUp)
    }

    @Test
    fun systemComposeBackKeepsTheDraft() {
        showDm(ComposeStyle.FULL_SCREEN, KeyboardStyle.SYSTEM)
        described("Compose").performClick()
        field().performTextInput("Hi")

        pressBack()
        assertFalse(editorOpen())
        assertFalse(softKeyboardUp)

        described("Compose").performClick()
        assertEquals("Hi", fieldText())
        rule.onAllNodesWithContentDescription("Back").onLast().performClick()
        assertFalse(editorOpen())
        described("Compose").performClick()
        assertEquals("Hi", fieldText())
        assertEquals(emptyList(), sent)
    }

    @Test
    fun systemComposeIgnoresBlankDrafts() {
        showDm(ComposeStyle.FULL_SCREEN, KeyboardStyle.SYSTEM)
        described("Compose").performClick()
        field().performTextInput("   ")
        described("Send").performClick()
        assertEquals(emptyList(), sent)
        assertTrue(editorOpen())
    }
}
