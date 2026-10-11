package app.photon.ui.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.photon.data.model.Conversation
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
import kotlin.test.assertTrue

/** A chat-list row marks a muted chat beside its time. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h413dp-xxhdpi")
class ConversationRowTest {
    private val rule = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(RegisterComposeHostActivity()).around(rule)

    private val now = System.currentTimeMillis() / 1000
    private val mutedAt = now - 600

    private fun conversation(name: String, unread: Int, muted: Boolean, lastTimestamp: Long) = Conversation(
        jid = "$name@g.us", name = name, isGroup = true, lastMessageId = null,
        lastTimestamp = lastTimestamp, unreadCount = unread, isMuted = muted,
        avatarUrl = null, updatedAt = lastTimestamp,
    )

    private fun showRows() {
        rule.setContent {
            PhotonTheme {
                Column {
                    ConversationRow(conversation("Neighbours", unread = 3, muted = true, lastTimestamp = mutedAt), Platform.WHATSAPP, onClick = {})
                    ConversationRow(conversation("Climbing", unread = 2, muted = false, lastTimestamp = now - 1800), Platform.WHATSAPP, onClick = {})
                }
            }
        }
    }

    @Test
    fun onlyMutedChatsShowTheMutedIcon() {
        showRows()
        rule.onAllNodesWithContentDescription("Muted").assertCountEquals(1)
        rule.onNode(hasText("Neighbours") and hasContentDescription("Muted")).assertExists()
        rule.onNode(hasText("Climbing") and hasContentDescription("Muted")).assertDoesNotExist()
    }

    @Test
    fun mutedChatsStillShowTheirUnreadCount() {
        showRows()
        rule.onNode(hasText("Neighbours") and hasText("3")).assertExists()
        rule.onNode(hasText("Climbing") and hasText("2")).assertExists()
    }

    /** The time ends the row, so times line up down the list; the count goes before it. */
    @Test
    fun theUnreadCountSitsBeforeTheTime() {
        showRows()
        val count = rule.onNodeWithText("2", useUnmergedTree = true).getBoundsInRoot()
        val time = rule.onNodeWithText(formatTimestamp(now - 1800), useUnmergedTree = true).getBoundsInRoot()
        assertTrue(count.right <= time.left, "count $count should sit left of time $time")
    }

    @Test
    fun theMutedIconSitsJustBeforeTheTime() {
        showRows()
        val icon = rule.onNodeWithContentDescription("Muted", useUnmergedTree = true).getBoundsInRoot()
        val time = rule.onNodeWithText(formatTimestamp(mutedAt), useUnmergedTree = true).getBoundsInRoot()
        assertTrue(icon.right <= time.left, "icon $icon should sit left of time $time")
        assertTrue(icon.top >= time.top && icon.bottom <= time.bottom, "icon $icon should fit the time's line $time")
    }
}
