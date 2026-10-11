package app.photon.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.test.core.app.ApplicationProvider
import app.photon.ui.preview.ChatListSample
import app.photon.ui.preview.DmThreadSample
import app.photon.ui.preview.GroupThreadSample
import app.photon.ui.preview.PreviewSamples
import app.photon.ui.shared.GroupRowStyle
import app.photon.ui.theme.PhotonTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    // The compose rule hosts content in a bare ComponentActivity. Register it
    // with Robolectric rather than shipping ui-test-manifest's activity in an
    // APK, so the test runs against both debug and release variants.
    private val registerHostActivity = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Context>()
            val host = ComponentName(app, ComponentActivity::class.java)
            shadowOf(app.packageManager).apply {
                addActivityIfNotPresent(host)
                addIntentFilterForActivity(
                    host,
                    IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
                )
            }
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(registerHostActivity).around(rule)

    private fun DpRect.toRect() = Rect(left.value, top.value, right.value, bottom.value)

    private fun bounds(text: String) = rule.onNodeWithText(text).getBoundsInRoot().toRect()

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
        rule.setContent { PhotonTheme { GroupThreadSample(GroupRowStyle.COLUMN) } }
        rule.onNodeWithText("failed · tap to retry").assertIsDisplayed()
        rule.onNodeWithText("Missed group video call", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("You")[0].assertIsDisplayed()
    }
}
