package app.photon.ui.shared

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.photon.data.ComposeStyle
import app.photon.data.KeyboardStyle
import app.photon.data.model.Message
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 0.5.0 rows in a thread: a system line, view-once media, a message
 * still on its way, a location, and the disappearing-messages header.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h413dp-xxhdpi")
class NewContentRowsTest {
    private val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(RegisterComposeHostActivity()).around(rule)

    private val mediaTaps = mutableListOf<String>()
    private val deleted = mutableListOf<String>()

    private val viewOnce = "[ view-once photo · open on your phone ]"
    private val unavailable = "[ waiting for this message · open on your phone ]"
    private val location = "Location · ${PreviewSamples.LOCATION_NAME}"

    private fun show(disappearingSeconds: Long = PreviewSamples.WEEK_SECONDS) {
        rule.setContent {
            PhotonTheme {
                ChatThread(
                    title = PreviewSamples.NEW_ROWS_TITLE,
                    platform = Platform.SIGNAL,
                    messages = PreviewSamples.newRowMessages,
                    isGroup = false,
                    participantNames = emptyMap(),
                    onSendText = { _, _ -> },
                    onSendAudio = null,
                    onSendMedia = null,
                    onBack = {},
                    onMediaTap = { msg: Message -> mediaTaps += msg.id },
                    onMessagesLoaded = null,
                    onReact = { _, _, _ -> },
                    onRetry = {},
                    onDelete = { msg: Message, _ -> deleted += msg.id },
                    onTitleClick = null,
                    supportsReply = true,
                    headerContent = null,
                    composeStyle = ComposeStyle.INLINE,
                    keyboardStyle = KeyboardStyle.SYSTEM,
                    disappearingSeconds = disappearingSeconds,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun longPress(text: String) {
        rule.onNodeWithText(text).performTouchInput { longClick() }
        rule.waitForIdle()
    }

    private fun shown(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theHeaderSaysMessagesDisappear() {
        show()
        rule.onNodeWithText(PreviewSamples.NEW_ROWS_TITLE).assertExists()
        rule.onNodeWithText("disappearing · 1 week").assertExists()
    }

    @Test
    fun noTimerNoLine() {
        show(disappearingSeconds = 0)
        rule.onNodeWithText(PreviewSamples.NEW_ROWS_TITLE).assertExists()
        rule.onAllNodesWithText("disappearing ·", substring = true).assertCountEquals(0)
    }

    @Test
    fun aSystemLineHasNoMenu() {
        show()
        longPress(PreviewSamples.SYSTEM_LINE)
        assertTrue(!shown("Delete") && !shown("Reply"), "no menu for a system line")
    }

    @Test
    fun viewOnceOffersOnlyDeleteAndNeverOpens() {
        show()
        rule.onNodeWithText(viewOnce).performClick()
        assertEquals(emptyList(), mediaTaps)

        longPress(viewOnce)
        rule.onAllNodesWithText("Reply").assertCountEquals(0)
        rule.onAllNodesWithText("React").assertCountEquals(0)
        rule.onNodeWithText("Delete").performClick()
        assertEquals(listOf("n4"), deleted)
    }

    @Test
    fun aMessageOnItsWayOffersOnlyDelete() {
        show()
        longPress(unavailable)
        rule.onAllNodesWithText("Reply").assertCountEquals(0)
        rule.onAllNodesWithText("React").assertCountEquals(0)
        rule.onNodeWithText("Delete").assertExists()
    }

    /** A location names itself, gives its coordinates, and opens in a map app. */
    @Test
    fun aLocationOpensInAMapApp() {
        show()
        rule.onNodeWithText("51.5014, -0.1419").assertExists()
        rule.onNodeWithText(location).performClick()

        val opened = shadowOf(rule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(
            "geo:51.501364,-0.141890?q=51.501364,-0.141890(Buckingham%20Palace)",
            opened.dataString,
        )
        assertEquals(emptyList(), mediaTaps)
    }

    @Test
    fun aLocationIsAMessageLikeAnyOther() {
        show()
        longPress(location)
        listOf("Reply", "React", "Delete").forEach { rule.onNodeWithText(it).assertExists() }
    }

    /** With no map app installed, tapping does nothing (and doesn't crash). */
    @Test
    fun withoutAMapAppNothingOpens() {
        show()
        shadowOf(rule.activity.application).checkActivities(true)
        rule.onNodeWithText(location).performClick()
        assertNull(shadowOf(rule.activity).nextStartedActivity)
    }
}
