package app.photon.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.photon.chat.FakeChatBackend
import app.photon.chat.FakeChatBackend.Companion.conversation
import app.photon.data.model.Platform
import app.photon.ui.RegisterComposeHostActivity
import app.photon.ui.home.PlatformsScreen
import app.photon.ui.theme.PhotonTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/** Archived: every platform's archived chats, on a page of their own, opened from the picker. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h413dp-xxhdpi")
class ArchivedChatsScreenTest {
    private val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(RegisterComposeHostActivity()).around(rule)

    private val whatsapp = FakeChatBackend(Platform.WHATSAPP)
    private val signal = FakeChatBackend(Platform.SIGNAL)
    private val sms = FakeChatBackend(Platform.SMS)
    private val backends = listOf(whatsapp, signal, sms).associateBy { it.platform }

    private val opened = mutableListOf<Pair<Platform, String>>()
    private var backs = 0

    private fun show() {
        rule.setContent {
            PhotonTheme {
                ArchivedChatsScreen(backends::get, onChat = { p, jid -> opened += p to jid }, onBack = { backs++ })
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun listsEveryPlatformsArchivedChatsAndOpensEachOnItsOwn() {
        whatsapp.conversations.value = listOf(
            conversation("a@s.whatsapp.net", "Alice", lastTimestamp = 20).copy(isArchived = true),
            conversation("c@s.whatsapp.net", "Carol", lastTimestamp = 40),
        )
        signal.conversations.value = listOf(conversation("sig", "Bob", lastTimestamp = 30).copy(isArchived = true))
        sms.conversations.value = listOf(conversation("+447700900123", "Mum", lastTimestamp = 10))
        show()

        rule.onNodeWithText("Archived").assertExists()
        rule.onAllNodesWithText("Carol").assertCountEquals(0)
        rule.onAllNodesWithText("Mum").assertCountEquals(0)
        rule.onNodeWithText("Bob").performClick()
        rule.onNodeWithText("Alice").performClick()
        assertEquals(listOf(Platform.SIGNAL to "sig", Platform.WHATSAPP to "a@s.whatsapp.net"), opened)
    }

    /** A secondary page: Back on top, and none of the main list's bottom bar. */
    @Test
    fun backIsTheWayOut() {
        whatsapp.conversations.value = listOf(conversation("a@s.whatsapp.net", "Alice").copy(isArchived = true))
        show()
        rule.onAllNodesWithContentDescription("Settings").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("New message").assertCountEquals(0)
        rule.onAllNodesWithText("ALL CHATS").assertCountEquals(0)
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun nothingArchivedSaysSo() {
        whatsapp.conversations.value = listOf(conversation("a@s.whatsapp.net", "Alice"))
        show()
        rule.onNodeWithText("no archived chats").assertExists()
        rule.onNodeWithContentDescription("Back").assertExists()
    }

    @Test
    fun thePickerOpensArchivedBelowThePlatforms() {
        var archived = 0
        val picked = mutableListOf<Platform?>()
        rule.setContent {
            PhotonTheme { PlatformsScreen(onPick = { picked += it }, onArchived = { archived++ }, onBack = {}) }
        }
        rule.onNodeWithText("Archived").performClick()
        assertEquals(1, archived)
        assertEquals(emptyList(), picked, "Archived isn't a filter")
    }
}
