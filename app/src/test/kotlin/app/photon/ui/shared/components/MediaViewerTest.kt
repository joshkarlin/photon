package app.photon.ui.shared.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.photon.ui.RegisterComposeHostActivity
import app.photon.ui.theme.PhotonTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/** The media viewer has one way out: Back. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h413dp-xxhdpi")
class MediaViewerTest {
    private val rule = createAndroidComposeRule<ComponentActivity>()
    private val files = TemporaryFolder()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(files).around(RegisterComposeHostActivity()).around(rule)

    @Test
    fun tappingTheMediaDoesNotCloseItButBackDoes() {
        val file = files.newFile("notes.pdf")
        var dismissed = 0
        rule.setContent {
            PhotonTheme {
                MediaViewer(
                    messageId = "m",
                    mediaMime = "application/pdf",
                    existingPath = file.path,
                    onDownloadMedia = { null },
                    onDismiss = { dismissed++ },
                )
            }
        }
        rule.onNodeWithText("Downloaded: notes.pdf").performClick()
        assertEquals(0, dismissed)
        rule.onNodeWithText("SAVE TO DEVICE").assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, dismissed)
    }
}
