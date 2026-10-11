package app.photon.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** Reset wipes a platform from Photon, so it says what happens and waits for RESET. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h413dp-xxhdpi")
class ResetConfirmTest {
    private val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(RegisterComposeHostActivity()).around(rule)

    private var resets = 0
    private var backs = 0

    private fun showWhatsApp() = rule.setContent {
        PhotonTheme {
            WhatsAppSettingsContent(
                state = "connected", onBack = { backs++ }, onLink = {}, onRefresh = {}, onReset = { resets++ },
            )
        }
    }

    private fun showSignal() = rule.setContent {
        PhotonTheme {
            SignalSettingsContent(
                registered = true, state = "connected", phone = "+447700900123", deviceId = 2,
                onBack = { backs++ }, onLink = {}, onRefresh = {}, onReset = { resets++ },
            )
        }
    }

    private fun shown(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** The system's Back, once the page on screen has composed. */
    private fun systemBack() {
        rule.waitForIdle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun whatsAppResetSaysWhatHappensAndWaitsForReset() {
        showWhatsApp()
        rule.onNodeWithText("Reset").performClick()

        rule.onNodeWithText("Reset WhatsApp").assertExists()
        rule.onNodeWithText(RESET_WHATSAPP_WARNING).assertExists()
        rule.onNodeWithContentDescription("Back").assertExists()
        assertEquals(0, resets, "nothing reset before confirming")

        rule.onNodeWithText("RESET").performClick()
        assertEquals(1, resets)
        assertEquals(0, backs)
    }

    @Test
    fun signalResetSaysWhatHappensAndWaitsForReset() {
        showSignal()
        rule.onNodeWithText("Reset").performClick()

        rule.onNodeWithText("Reset Signal").assertExists()
        rule.onNodeWithText(RESET_SIGNAL_WARNING).assertExists()
        assertEquals(0, resets, "nothing reset before confirming")

        rule.onNodeWithText("RESET").performClick()
        assertEquals(1, resets)
    }

    @Test
    fun backCancelsTheResetAndReturnsToThePlatform() {
        showWhatsApp()
        rule.onNodeWithText("Reset").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(false, shown(RESET_WHATSAPP_WARNING))
        rule.onNodeWithText("Refresh Connection").assertExists()

        // The system's Back too, without leaving the platform's page.
        rule.onNodeWithText("Reset").performClick()
        systemBack()
        assertEquals(false, shown(RESET_WHATSAPP_WARNING))
        rule.onNodeWithText("Refresh Connection").assertExists()

        assertEquals(0, resets)
        assertEquals(0, backs, "Back left the confirmation, not the platform's page")
    }

    @Test
    fun signalBackCancelsTheReset() {
        showSignal()
        rule.onNodeWithText("Reset").performClick()
        systemBack()
        rule.onNodeWithText("Refresh Connection").assertExists()
        rule.onAllNodesWithContentDescription("Back").assertCountEquals(1)
        assertEquals(0, resets)
        assertEquals(0, backs)
    }

    /** An unlinked platform has nothing to reset: Link Device opens pairing straight away. */
    @Test
    fun linkDeviceNeedsNoConfirmation() {
        var links = 0
        rule.setContent {
            PhotonTheme {
                SignalSettingsContent(
                    registered = false, state = "disconnected", phone = null, deviceId = null,
                    onBack = {}, onLink = { links++ }, onRefresh = {}, onReset = { resets++ },
                )
            }
        }
        rule.onAllNodesWithText("Reset").assertCountEquals(0)
        rule.onNodeWithText("Link Device").performClick()
        assertEquals(1, links)
        assertEquals(0, resets)
    }
}
