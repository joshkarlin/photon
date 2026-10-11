package app.photon.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import app.photon.ui.preview.ChatListSample
import app.photon.ui.preview.DmThreadSample
import app.photon.ui.preview.GroupThreadSample
import app.photon.ui.preview.PreviewSamples
import app.photon.ui.theme.PhotonTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/**
 * Screens at the LP3's 360x413dp viewport, using the design-preview samples.
 * Native graphics: the SDK's font lookup (`SystemFonts`) needs it under
 * Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h413dp-xxhdpi")
class Lp3LayoutTest {
    private val rule = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(RegisterComposeHostActivity()).around(rule)

    private fun DpRect.toRect() = Rect(left.value, top.value, right.value, bottom.value)

    private fun bounds(text: String) = rule.onNodeWithText(text).getBoundsInRoot().toRect()

    /** Bounds of the text itself, not the clickable message row it merges into. */
    private fun textBounds(text: String) =
        rule.onNodeWithText(text, useUnmergedTree = true).getBoundsInRoot().toRect()

    private fun boundsOf(description: String) =
        rule.onNodeWithContentDescription(description).getBoundsInRoot().toRect()

    @Test
    fun longChatTitleStaysClearOfBack() {
        rule.setContent { PhotonTheme { DmThreadSample() } }
        val title = bounds(PreviewSamples.LONG_DM_TITLE)
        val back = boundsOf("Back")
        assertTrue(title.left >= back.right, "title $title overlaps back $back")
        assertTrue(title.right <= 360f, "title $title runs off screen")
    }

    @Test
    fun longListTitleAndSettingsDoNotOverlap() {
        rule.setContent { PhotonTheme { ChatListSample() } }
        val title = bounds("All Chats ▾")
        val settings = boundsOf("Settings")
        assertTrue(title.right <= settings.left, "title $title overlaps settings $settings")
    }

    @Test
    fun groupThreadShowsCallsAndFailures() {
        rule.setContent { PhotonTheme { GroupThreadSample() } }
        rule.onNodeWithText("failed · tap to retry").assertIsDisplayed()
        rule.onNodeWithText("Missed group video call", substring = true).assertIsDisplayed()
    }

    @Test
    fun groupThreadNamesIncomingBurstsOnly() {
        rule.setContent { PhotonTheme { GroupThreadSample() } }
        // LP2 style: "Name  12:36" above each incoming burst, nothing above
        // your own, and no sender column.
        rule.onAllNodesWithText("Bob")[0].assertIsDisplayed()
        rule.onAllNodesWithText("You").assertCountEquals(0)
        val name = textBounds("Maximilian Featherstonehaugh")
        val body = textBounds("I can take three in the van, leaving at 9")
        assertTrue(name.bottom <= body.top, "name $name should sit above body $body")
        assertTrue(body.left <= name.left, "body $body should start under name $name, not beside it")
    }
}
