package app.photon.nav

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
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
import kotlin.test.assertEquals

/** New message opens over the chat list, and a chat picked there takes its place. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h413dp-xxhdpi")
class NewMessageRouteTest {
    private val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(RegisterComposeHostActivity()).around(rule)

    private lateinit var nav: NavHostController

    private fun showGraph() {
        rule.setContent {
            PhotonTheme {
                nav = rememberNavController()
                PhotonNavGraph(nav)
            }
        }
        rule.waitForIdle()
    }

    private fun stack() = nav.currentBackStack.value.mapNotNull { it.destination.route }

    @Test
    fun newMessageOpensOverTheChatListOnce() {
        showGraph()
        rule.runOnIdle {
            nav.openNewMessage()
            nav.openNewMessage()
        }
        rule.waitForIdle()

        assertEquals(listOf(Routes.CHATS, Routes.NEW_MESSAGE), stack())
        rule.onNodeWithText("New message").assertExists()
    }

    /** Back from a chat picked in New message goes to the chat list, not back to the search. */
    @Test
    fun aPickedChatReplacesNewMessage() {
        showGraph()
        rule.runOnIdle { nav.openNewMessage() }
        rule.waitForIdle()
        rule.runOnIdle { nav.openChatFromNewMessage(Platform.SMS, "+447700900123") }
        rule.waitForIdle()

        assertEquals(listOf(Routes.CHATS, Routes.CHAT), stack())
        assertEquals("+447700900123", Uri.decode(nav.currentBackStackEntry?.arguments?.getString("jid")))
        rule.runOnIdle { nav.popBackStack() }
        assertEquals(listOf(Routes.CHATS), stack())
    }
}
