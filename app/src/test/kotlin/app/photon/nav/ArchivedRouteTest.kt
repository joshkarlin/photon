package app.photon.nav

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** Archived opens from the platform picker in its place, so Back returns to the chat list. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h413dp-xxhdpi")
class ArchivedRouteTest {
    private val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(RegisterComposeHostActivity()).around(rule)

    private lateinit var nav: NavHostController

    private fun stack() = nav.currentBackStack.value.mapNotNull { it.destination.route }

    @Test
    fun archivedReplacesThePickerAndBackReturnsToTheList() {
        rule.setContent {
            PhotonTheme {
                nav = rememberNavController()
                PhotonNavGraph(nav)
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { nav.navigate(Routes.chats(Platform.SIGNAL)) { popUpTo(Routes.CHATS) { inclusive = true } } }
        rule.runOnIdle { nav.navigate(Routes.PLATFORMS) }
        rule.waitForIdle()

        rule.onNodeWithText("Archived").performClick()
        rule.waitForIdle()
        assertEquals(listOf(Routes.CHATS, Routes.ARCHIVED), stack())
        // No sessions here: the page is up, its list still loading.
        rule.onNodeWithText("Archived").assertExists()

        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        assertEquals(listOf(Routes.CHATS), stack())
        assertEquals(Platform.SIGNAL.name, nav.currentBackStackEntry?.arguments?.getString("platform"), "the list it came from")
    }

    /** A chat opened from Archived returns to Archived. */
    @Test
    fun aChatFromArchivedReturnsToIt() {
        rule.setContent {
            PhotonTheme {
                nav = rememberNavController()
                PhotonNavGraph(nav)
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            nav.navigate(Routes.PLATFORMS)
            nav.openArchived()
            nav.navigate(Routes.chat(Platform.WHATSAPP, "a@s.whatsapp.net"))
        }
        rule.waitForIdle()
        assertEquals(listOf(Routes.CHATS, Routes.ARCHIVED, Routes.CHAT), stack())
        rule.runOnIdle { nav.popBackStack() }
        assertEquals(listOf(Routes.CHATS, Routes.ARCHIVED), stack())
    }
}
