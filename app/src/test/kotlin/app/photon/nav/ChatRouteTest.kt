package app.photon.nav

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import app.photon.data.model.Platform
import app.photon.service.NotificationHelper
import app.photon.ui.RegisterComposeHostActivity
import app.photon.ui.theme.PhotonTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every chat, on every platform, is the one route; a notification opens it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h413dp-xxhdpi")
class ChatRouteTest {
    private val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(RegisterComposeHostActivity()).around(rule)

    // A Signal group id: base64, with the characters a path can't hold.
    private val group = "ab/c+d=="

    @Test
    fun aNotificationsChatIsOpenedOverTheChatList() {
        val intent = Intent()
            .putExtra(NotificationHelper.EXTRA_PLATFORM, "SIGNAL")
            .putExtra(NotificationHelper.EXTRA_CONVERSATION, group)
        val link = ChatDeepLink.from(intent)!!
        lateinit var nav: NavHostController
        var handled = false
        rule.setContent {
            PhotonTheme {
                nav = rememberNavController()
                PhotonNavGraph(nav, deepLink = link, onDeepLinkHandled = { handled = true })
            }
        }
        rule.waitForIdle()

        val chat = nav.currentBackStackEntry!!
        assertEquals(Routes.CHAT, chat.destination.route)
        assertEquals("SIGNAL", chat.arguments?.getString("platform"))
        assertEquals(group, Uri.decode(chat.arguments?.getString("jid")))
        assertEquals(Routes.CHATS, nav.previousBackStackEntry?.destination?.route)
        assertTrue(handled)
    }

    @Test
    fun anUnknownPlatformIsNoLink() {
        val intent = Intent()
            .putExtra(NotificationHelper.EXTRA_PLATFORM, "TELEGRAM")
            .putExtra(NotificationHelper.EXTRA_CONVERSATION, group)
        assertNull(ChatDeepLink.from(intent))
        assertNull(Routes.platformOf("whatsapp"), "platform names are the enum's")
        assertEquals(Platform.SMS, Routes.platformOf("SMS"))
    }

    @Test
    fun switchingPlatformLeavesOneChatListAndGatesPairing() {
        lateinit var nav: NavHostController
        rule.setContent {
            PhotonTheme {
                nav = rememberNavController()
                PhotonNavGraph(nav)
            }
        }

        fun pick(row: String) {
            rule.onNodeWithText(titleNow(nav)).performClick()
            rule.onNodeWithText(row).performClick()
            rule.waitForIdle()
        }

        pick("SMS")
        assertEquals(Routes.CHATS, nav.currentBackStackEntry?.destination?.route)
        assertEquals("SMS", nav.currentBackStackEntry?.arguments?.getString("platform"))
        assertNull(nav.previousBackStackEntry, "the old list was replaced, not stacked")
        rule.onNodeWithText("SMS ▾").assertExists()

        // No WhatsApp session has reported logged_out: its chats, not pairing.
        pick("WhatsApp")
        assertEquals("WHATSAPP", nav.currentBackStackEntry?.arguments?.getString("platform"))

        // Signal isn't linked here: pairing.
        pick("Signal")
        assertEquals(Routes.SIGNAL_PAIRING, nav.currentBackStackEntry?.destination?.route)
    }

    /** The current list's title, which opens the platform picker. */
    private fun titleNow(nav: NavHostController): String =
        (Routes.platformOf(nav.currentBackStackEntry?.arguments?.getString("platform"))?.label ?: "All Chats") + " ▾"
}
