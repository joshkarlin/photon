package app.photon.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import app.photon.ui.preview.ChatListSample
import app.photon.ui.preview.DmThreadSample
import app.photon.ui.preview.GroupThreadSample
import app.photon.ui.preview.PreviewSamples
import app.photon.ui.settings.SettingsScreen
import app.photon.ui.shared.CHAT_LIST_TAG
import app.photon.ui.shared.ChatListLayout
import app.photon.ui.shared.formatTimestamp
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

    /** LightOS's page margin, two grid units of the 27 across. */
    private val margin = 360f / 27 * 2

    /** The list spans the screen, so its scroll bar sits by the edge; rows inset their content. */
    @Test
    fun chatListSpansTheScreenAndRowsStartAtTheMargin() {
        rule.setContent {
            PhotonTheme {
                ChatListLayout(
                    filter = "SMS",
                    conversations = PreviewSamples.conversations,
                    platformOf = PreviewSamples::platformOf,
                    onChat = {},
                )
            }
        }
        val list = rule.onNodeWithTag(CHAT_LIST_TAG).getBoundsInRoot().toRect()
        assertEquals(0f, list.left, 0.5f)
        assertEquals(360f, list.right, 0.5f)
        assertEquals(margin, textBounds("Mum").left, 0.5f)
    }

    /** Times form one column at the margin, whether or not a row has an unread count. */
    @Test
    fun timesEndEveryRowAtTheMargin() {
        rule.setContent { PhotonTheme { ChatListSample() } }
        // The first rows have counts (one muted); the next ones don't.
        PreviewSamples.conversations.take(5).forEach { conv ->
            rule.onAllNodesWithText(formatTimestamp(conv.lastTimestamp), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .forEach { node -> assertEquals(360f - margin, node.boundsInRoot.right / density, 0.5f, conv.name) }
        }
    }

    private val density get() = rule.density.density

    /** LightOS Settings: large labels at the margin, rows five units apart. */
    @Test
    fun menuRowsSitAtTheMarginFiveUnitsApart() {
        rule.setContent { PhotonTheme { SettingsScreen(onBack = {}) } }
        val connections = bounds("Connections")
        val chat = bounds("Chat")
        assertEquals(margin, connections.left, 0.5f)
        assertEquals(5 * 360f / 27, chat.top - connections.top, 0.5f)
    }

    @Test
    fun longChatTitleStaysClearOfBack() {
        rule.setContent { PhotonTheme { DmThreadSample() } }
        val title = bounds(PreviewSamples.LONG_DM_TITLE)
        val back = boundsOf("Back")
        assertTrue(title.left >= back.right, "title $title overlaps back $back")
        assertTrue(title.right <= 360f, "title $title runs off screen")
    }

    /**
     * As LightOS Messages: the list starts below a top bar's height left
     * empty, and the bottom bar puts Settings at the left margin and the
     * filter in the middle.
     */
    @Test
    fun chatListHasABottomBarInsteadOfATopBar() {
        rule.setContent { PhotonTheme { ChatListSample() } }
        val unit = 360f / 27
        assertEquals(3 * unit, rule.onNodeWithTag(CHAT_LIST_TAG).getBoundsInRoot().toRect().top, 0.5f)
        val settings = boundsOf("Settings")
        assertEquals(margin, settings.left, 0.5f)
        assertTrue(settings.top >= 413f - 4 * unit, "settings $settings should sit in the bottom bar")
        val filter = bounds("ALL CHATS")
        assertEquals(180f, (filter.left + filter.right) / 2, 1f)
        assertTrue(filter.top >= 413f - 4 * unit, "filter $filter should sit in the bottom bar")
    }

    @Test
    fun groupThreadShowsCallsAndFailures() {
        rule.setContent { PhotonTheme { GroupThreadSample() } }
        rule.onNodeWithText("failed · tap to retry").assertIsDisplayed()
        rule.onNodeWithText("Missed group video call", substring = true).assertIsDisplayed()
    }

    /** In a DM the title already names the other party. */
    @Test
    fun dmReplyQuotesDoNotRepeatTheName() {
        rule.setContent { PhotonTheme { DmThreadSample() } }
        rule.onNodeWithText("↳ Are we still on for tomorrow night?", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun groupReplyQuotesNameTheSender() {
        rule.setContent { PhotonTheme { GroupThreadSample() } }
        rule.onNodeWithText("↳ Alice: Who's driving on Saturday?", useUnmergedTree = true).assertIsDisplayed()
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
