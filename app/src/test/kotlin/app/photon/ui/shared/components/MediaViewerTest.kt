package app.photon.ui.shared.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import android.provider.MediaStore
import app.photon.ui.RegisterComposeHostActivity
import app.photon.ui.theme.PhotonTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The media viewer: Back is the one way out, and Save is the top bar's action. */
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
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, dismissed)
    }

    /** Save is the top bar's action, top-right; there's no bottom bar, so the media has the page. */
    @Test
    fun saveSitsTopRightAndTurnsToSaved() {
        val file = files.newFile("photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        rule.setContent {
            PhotonTheme {
                MediaViewer(messageId = "m", mediaMime = "image/jpeg", existingPath = file.path, onDownloadMedia = { null }, onDismiss = {})
            }
        }
        val save = rule.onNodeWithContentDescription("Save").getBoundsInRoot()
        val back = rule.onNodeWithContentDescription("Back").getBoundsInRoot()
        assertEquals(back.top, save.top)
        assertTrue(save.left > 300.dp, "save $save should sit top-right")
        rule.onAllNodesWithText("SAVE TO DEVICE").assertCountEquals(0)

        rule.onNodeWithContentDescription("Save").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("Saved").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithContentDescription("Save").assertCountEquals(0)
    }

    /** A document is saved under its own name, with the extension its type has. */
    @Test
    fun aDocumentIsSavedUnderItsRealName() {
        val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        val file = files.newFile("3EB0C5A1.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        rule.setContent {
            PhotonTheme {
                MediaViewer(
                    messageId = "m", mediaMime = docx, existingPath = file.path, fileName = "Q3 report",
                    onDownloadMedia = { null }, onDismiss = {},
                )
            }
        }
        rule.onNodeWithText("Downloaded: Q3 report.docx").assertExists()
        rule.onNodeWithContentDescription("Save").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("Saved").fetchSemanticsNodes().isNotEmpty() }

        val saved = shadowOf(rule.activity.contentResolver).insertStatements.single().contentValues
        assertEquals("Q3 report.docx", saved.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals(docx, saved.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("Download/Photon", saved.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
    }
}
