package app.photon.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.photon.data.ComposeStyle
import app.photon.data.KeyboardStyle
import app.photon.data.NotificationLevel
import app.photon.data.ScrollDial
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

/** Settings › Chat: Messages and Calls each set how their notifications alert. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h413dp-xxhdpi")
class ChatSettingsTest {
    private val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(RegisterComposeHostActivity()).around(rule)

    @Test
    fun messagesAndCallsAreSeparateRows() {
        val cycled = mutableListOf<String>()
        rule.setContent {
            PhotonTheme {
                ChatSettingsContent(
                    messageLevel = NotificationLevel.OFF,
                    callLevel = NotificationLevel.VIBRATE,
                    showThumbnails = true,
                    scrollDial = ScrollDial.MEDIUM,
                    composeStyle = ComposeStyle.INLINE,
                    keyboardStyle = KeyboardStyle.SYSTEM,
                    onBack = {},
                    onCycleMessages = { cycled += "messages" },
                    onCycleCalls = { cycled += "calls" },
                    onToggleThumbnails = {},
                    onCycleScrollDial = {},
                    onCycleComposeStyle = {},
                    onCycleKeyboardStyle = {},
                )
            }
        }
        rule.onAllNodesWithText("Notifications").assertCountEquals(0)
        rule.onNodeWithText("Off").assertExists()
        rule.onNodeWithText("Vibrate").assertExists()

        rule.onNodeWithText("Messages").performClick()
        rule.onNodeWithText("Calls").performClick()
        assertEquals(listOf("messages", "calls"), cycled)
    }
}
