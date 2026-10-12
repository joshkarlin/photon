package app.photon.ui.chat

import android.app.NotificationManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.photon.chat.FakeChatBackend
import app.photon.chat.FakeChatBackend.Companion.conversation
import app.photon.data.model.Platform
import app.photon.service.NotificationHelper
import app.photon.ui.RegisterComposeHostActivity
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

/** The one chat list: All Chats, or one platform's. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h413dp-xxhdpi")
class ChatListScreenTest {
    private val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(RegisterComposeHostActivity()).around(rule)

    private val whatsapp = FakeChatBackend(Platform.WHATSAPP)
    private val signal = FakeChatBackend(Platform.SIGNAL)
    private val sms = FakeChatBackend(Platform.SMS)
    private val backends = listOf(whatsapp, signal, sms).associateBy { it.platform }

    private val opened = mutableListOf<Pair<Platform, String>>()
    private var switched = 0
    private var settings = 0
    private var newMessages = 0
    private var filter by mutableStateOf<Platform?>(null)

    private fun show(filter: Platform?, withNewMessage: Boolean = false) {
        this.filter = filter
        rule.setContent {
            PhotonTheme {
                ChatListScreen(
                    this.filter, backends::get,
                    onChat = { p, jid -> opened += p to jid }, onSwitch = { switched++ }, onSettings = { settings++ },
                    onNewMessage = if (withNewMessage) { { newMessages++ } } else null,
                )
            }
        }
        rule.waitForIdle()
    }

    /** LightOS Messages' layout: no top bar; Settings, the filter and (later) New message along the bottom. */
    @Test
    fun theBottomBarHoldsSettingsAndTheFilter() {
        whatsapp.conversations.value = listOf(conversation("a@s.whatsapp.net", "Alice", lastTimestamp = 20))
        show(filter = null)
        rule.onNodeWithContentDescription("Settings").performClick()
        assertEquals(1, settings)
        rule.onAllNodesWithContentDescription("Back").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("New message").assertCountEquals(0)

        val labels = mapOf(null to "ALL CHATS", Platform.SMS to "SMS", Platform.WHATSAPP to "WHATSAPP", Platform.SIGNAL to "SIGNAL")
        labels.forEach { (platform, label) ->
            filter = platform
            rule.onNodeWithText(label).performClick()
        }
        assertEquals(labels.size, switched, "each filter label opens the platform picker")
    }

    /** The bar's right-hand slot starts a new message, on every filter. */
    @Test
    fun theBottomBarStartsANewMessage() {
        show(filter = null, withNewMessage = true)
        rule.onNodeWithContentDescription("New message").performClick()
        filter = Platform.SMS
        rule.waitForIdle()
        rule.onNodeWithContentDescription("New message").performClick()
        assertEquals(2, newMessages)
    }

    /** Settings stays in reach before anything has loaded, and on an empty list. */
    @Test
    fun loadingAndEmptyListsKeepTheBottomBar() {
        show(Platform.SIGNAL)
        rule.onNodeWithContentDescription("Settings").assertExists()
        signal.conversations.value = emptyList()
        rule.waitForIdle()
        rule.onNodeWithText("no messages yet").assertExists()
        rule.onNodeWithContentDescription("Settings").assertExists()
    }

    @Test
    fun allChatsListsEveryPlatformAndOpensEachChatOnItsOwn() {
        whatsapp.conversations.value = listOf(conversation("a@s.whatsapp.net", "Alice", lastTimestamp = 20))
        signal.conversations.value = listOf(conversation("sig", "Bob", lastTimestamp = 30))
        sms.conversations.value = listOf(conversation("+447700900123", "Mum", lastTimestamp = 10))
        show(filter = null)

        rule.onNodeWithText("ALL CHATS").performClick()
        assertEquals(1, switched, "the filter opens the platform picker")
        rule.onNodeWithText("Mum").performClick()
        rule.onNodeWithText("Bob").performClick()
        assertEquals(listOf(Platform.SMS to "+447700900123", Platform.SIGNAL to "sig"), opened)
    }

    @Test
    fun onePlatformsListShowsOnlyItsChats() {
        signal.conversations.value = listOf(conversation("sig", "Bob", lastTimestamp = 30))
        whatsapp.conversations.value = listOf(conversation("a@s.whatsapp.net", "Alice", lastTimestamp = 20))
        show(Platform.SIGNAL)

        rule.onNodeWithText("SIGNAL").assertExists()
        rule.onAllNodesWithText("Alice").assertCountEquals(0)
        rule.onNodeWithText("Bob").performClick()
        assertEquals(listOf(Platform.SIGNAL to "sig"), opened)
    }

    @Test
    fun emptyListsSayWhyByPlatform() {
        whatsapp.conversations.value = emptyList()
        show(Platform.WHATSAPP)
        // No session running: WhatsApp's sync status shows its connection.
        rule.onNodeWithText("connecting").assertExists()
    }

    @Test
    fun anEmptySignalListSaysNoMessagesYet() {
        signal.conversations.value = emptyList()
        show(Platform.SIGNAL)
        rule.onNodeWithText("no messages yet").assertExists()
    }

    @Test
    fun archivedChatsLeaveAllChatsAndEachPlatformsList() {
        whatsapp.conversations.value = listOf(
            conversation("a@s.whatsapp.net", "Alice", lastTimestamp = 20).copy(isArchived = true),
            conversation("b@s.whatsapp.net", "Bob", lastTimestamp = 10),
        )
        show(filter = null)
        rule.onAllNodesWithText("Alice").assertCountEquals(0)
        rule.onNodeWithText("Bob").assertExists()

        filter = Platform.WHATSAPP
        rule.waitForIdle()
        rule.onAllNodesWithText("Alice").assertCountEquals(0)
        rule.onNodeWithText("Bob").assertExists()
    }

    /** WhatsApp's sync status is for a list with no chats yet, not one whose chats are all archived. */
    @Test
    fun aListOfOnlyArchivedChatsSaysNoMessages() {
        whatsapp.conversations.value = listOf(conversation("a@s.whatsapp.net", "Alice", lastTimestamp = 20).copy(isArchived = true))
        show(Platform.WHATSAPP)
        rule.onNodeWithText("no messages").assertExists()
        rule.onAllNodesWithText("connecting").assertCountEquals(0)
    }

    /** Archived chats still notify, so the list still clears their notifications once they're read. */
    @Test
    fun theListStillClearsArchivedChatsNotifications() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NotificationHelper.showMessageNotification(context, "Alice", "hi", Platform.WHATSAPP, "a@s.whatsapp.net")
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        assertEquals(1, manager.allNotifications.size)

        whatsapp.conversations.value = listOf(conversation("a@s.whatsapp.net", "Alice", lastTimestamp = 20).copy(isArchived = true))
        show(filter = null)
        assertEquals(0, manager.allNotifications.size)
    }

    @Test
    fun mergingWaitsForAnyPlatformAndSortsNewestFirst() {
        assertNull(mergeChats(listOf(Platform.WHATSAPP to null, Platform.SMS to null)))
        val merged = mergeChats(
            listOf(
                Platform.WHATSAPP to listOf(conversation("w1", "w1", 5), conversation("w2", "w2", 1)),
                Platform.SIGNAL to null,
                Platform.SMS to listOf(conversation("s1", "s1", 3)),
            ),
        )!!
        assertEquals(listOf("w1", "s1", "w2"), merged.map { it.conversation.jid })
        assertEquals(listOf(Platform.WHATSAPP, Platform.SMS, Platform.WHATSAPP), merged.map { it.platform })
    }
}
