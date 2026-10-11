package app.photon.ui.newmessage

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.photon.chat.Reach
import app.photon.data.KeyboardStyle
import app.photon.data.ScrollDial
import app.photon.data.model.Platform
import app.photon.data.names.DisplayNames
import app.photon.ui.RegisterComposeHostActivity
import app.photon.ui.shared.ScrollDialEvent
import app.photon.ui.shared.ScrollDialState
import app.photon.ui.theme.PhotonTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/** New message at LP3 size, over sample people and fake platforms. Nothing is sent. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h413dp-xxhdpi")
class NewMessageScreenTest {
    private val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(RegisterComposeHostActivity()).around(rule)

    private var savedCountry = ""
    private val opened = mutableListOf<Pair<Platform, String>>()
    private val reached = mutableListOf<Pair<Platform, String>>()

    @Before
    fun setCountry() {
        savedCountry = DisplayNames.defaultCountry
        DisplayNames.defaultCountry = "GB"
    }

    @After
    fun restoreCountry() {
        DisplayNames.defaultCountry = savedCountry
    }

    private fun show(keyboard: KeyboardStyle = KeyboardStyle.SYSTEM, reach: suspend (Platform, String) -> Reach = NewMessageSamples.reach) {
        rule.setContent {
            PhotonTheme {
                NewMessageContent(
                    index = NewMessageSamples.index(),
                    keyboard = keyboard,
                    reach = { platform, phone ->
                        reached += platform to phone
                        reach(platform, phone)
                    },
                    onOpenChat = { platform, jid -> opened += platform to jid },
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()
    }

    private val field get() = rule.onNode(hasSetTextAction())

    private fun type(text: String) {
        field.performTextReplacement(text)
        rule.waitForIdle()
    }

    // ─── Searching ──────────────────────────────────────────────────

    @Test
    fun theFieldIsReadyToTypeIntoAtOnce() {
        show()
        field.assertIsFocused()
        rule.onNodeWithText("New message").assertExists()
        rule.onNodeWithText("Name or number").assertExists()
    }

    @Test
    fun matchesFollowWhatIsTyped() {
        show()
        field.performTextInput("mo")
        rule.waitForIdle()
        rule.onNodeWithText("WhatsApp · SMS").assertExists()
        // People (contacts and chats alike), then the group; scrolled to, as
        // only a few rows fit.
        val matches = rule.onNode(hasScrollToIndexAction())
        val expected = listOf("Mo Salah", "Moe", "Monica Geller", "Ramona Flowers", "Amos Burton", "Monday climbing")
        expected.forEachIndexed { i, name ->
            matches.performScrollToIndex(i)
            rule.onNodeWithText(name).assertExists()
        }
        rule.onNodeWithText("WhatsApp group").assertExists()

        type("mon")
        rule.onAllNodesWithText("Mo Salah").assertCountEquals(0)
        rule.onNodeWithText("Monica Geller").assertExists()
    }

    @Test
    fun nothingTypedShowsNothingAndNoMatchSaysSo() {
        show()
        rule.onAllNodesWithText("Mo Salah").assertCountEquals(0)
        rule.onAllNodesWithText("No matches").assertCountEquals(0)
        type("xyz")
        rule.onNodeWithText("No matches").assertExists()
    }

    /** Enter puts the keyboard away so the matches can be read; a tap on the field brings it back. */
    @Test
    fun enterDismissesTheKeyboardAndKeepsTheMatches() {
        show()
        type("mo")
        field.performImeAction()
        rule.waitForIdle()
        field.assertIsNotFocused()
        rule.onNodeWithText("Mo Salah").assertExists()

        field.performClick()
        rule.waitForIdle()
        field.assertIsFocused()
    }

    @Test
    fun turningTheDialPutsTheKeyboardAway() {
        show()
        type("mo")
        ScrollDialState.emit(ScrollDialEvent(1, ScrollDial.MEDIUM))
        rule.waitForIdle()
        field.assertIsNotFocused()
    }

    @Test
    fun theKeypadSwitchesToNumbersAndBack() {
        show()
        field.performImeAction()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Keypad").performClick()
        rule.waitForIdle()
        field.assertIsFocused()
        rule.onNodeWithText("ABC").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Keypad").assertExists()
    }

    /** The LP3 keyboard opens on its number layer from the keypad (the layer its 123 key opens). */
    @Test
    fun theLp3KeyboardOpensOnItsNumberLayer() {
        show(KeyboardStyle.LIGHT)
        field.assertIsFocused()
        rule.onNodeWithContentDescription("Keypad").performClick()
        rule.waitForIdle()
        // The bar follows the keyboard's layer: it offers letters again.
        rule.onNodeWithText("ABC").assertExists()
        rule.onNodeWithText("ABC").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Keypad").assertExists()
    }

    /** The LP3 keyboard is docked while the field has focus; Back puts it away and a tap brings it back. */
    @Test
    fun theLp3KeyboardComesAndGoesWithTheField() {
        show(KeyboardStyle.LIGHT)
        rule.onNodeWithText("Q").assertExists()

        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        field.assertIsNotFocused()
        rule.onAllNodesWithText("Q").assertCountEquals(0)

        field.performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Q").assertExists()
    }

    // ─── Picking ────────────────────────────────────────────────────

    @Test
    fun aGroupOpens() {
        show()
        type("climb")
        rule.onNodeWithText("Monday climbing").performClick()
        assertEquals(listOf(Platform.WHATSAPP to NewMessageSamples.climbing.conversation.jid), opened)
    }

    @Test
    fun someoneWithChatsOnOnePlatformOnlyOpensItAtOnce() {
        show()
        type("ramona")
        rule.onNodeWithText("Ramona Flowers").performClick()
        assertEquals(listOf(Platform.SIGNAL to NewMessageSamples.ramona.conversation.jid), opened)
        assertEquals(emptyList(), reached)
    }

    /** Chats on two platforms: Send via, each platform with what it can do, greyed when it can't. */
    @Test
    fun someoneWithChatsOnTwoPlatformsIsAskedWhere() {
        show()
        type("mo sa")
        rule.onNodeWithText("Mo Salah").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Mo Salah").assertExists() // the title
        rule.onAllNodesWithText("existing chat").assertCountEquals(2)
        rule.onNodeWithText("not known to Signal").assertExists()
        assertEquals(listOf(Platform.SIGNAL to "+447700900123"), reached)

        rule.onNodeWithText("Signal").performClick()
        assertEquals(emptyList(), opened, "an unavailable platform does nothing")
        rule.onNodeWithText("SMS").performClick()
        assertEquals(listOf(Platform.SMS to "+447700900123"), opened)
    }

    @Test
    fun aContactWithoutChatsIsOfferedNewChats() {
        show()
        type("monica")
        rule.onNodeWithText("Monica Geller").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("new chat").assertCountEquals(2)
        rule.onNodeWithText("WhatsApp").performClick()
        assertEquals(listOf(Platform.WHATSAPP to "447700900456@s.whatsapp.net"), opened)
    }

    @Test
    fun aTypedNumberCanBeSentTo() {
        show()
        type("+44 7700 900321")
        rule.onNodeWithText("Send to +44 7700 900321").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("+44 7700 900321").assertExists() // the title
        rule.onNodeWithText("SMS").performClick()
        assertEquals(listOf(Platform.SMS to "+447700900321"), opened)
    }

    @Test
    fun aPlatformStillCheckingCantBePicked() {
        show(reach = { _, _ -> kotlinx.coroutines.awaitCancellation() })
        type("monica")
        rule.onNodeWithText("Monica Geller").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("checking…").assertCountEquals(3)
        rule.onNodeWithText("WhatsApp").performClick()
        assertEquals(emptyList(), opened)
    }

    /** Back from Send via returns to the matches as they were, without bringing the keyboard back. */
    @Test
    fun backFromSendViaKeepsTheSearch() {
        show()
        type("mo sa")
        rule.onNodeWithText("Mo Salah").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("mo sa").assertExists()
        rule.onNodeWithText("WhatsApp · SMS").assertExists()
        field.assertIsNotFocused()
    }
}
