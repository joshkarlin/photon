package app.photon.ui.shared

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.photon.data.ComposeStyle
import app.photon.data.KeyboardStyle
import app.photon.data.model.Platform
import app.photon.ui.RegisterComposeHostActivity
import app.photon.ui.preview.PreviewSamples
import app.photon.ui.shared.components.ATTACHABLE_FILE_TYPES
import app.photon.ui.shared.components.captureFile
import app.photon.ui.theme.PhotonTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Attach's sheet in the thread: Camera, Photos and Files, as the platform and phone allow. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h413dp-xxhdpi")
class AttachSheetTest {
    private val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(RegisterComposeHostActivity()).around(rule)

    private val sentMedia = mutableListOf<Pair<String, String>>()

    /** Lets an app answer "take a photo", as a camera app would. */
    private fun installCameraApp() {
        val resolve = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.example.camera"
                name = "com.example.camera.Capture"
            }
        }
        shadowOf(rule.activity.packageManager).addResolveInfoForIntent(Intent(MediaStore.ACTION_IMAGE_CAPTURE), resolve)
    }

    private fun showDm(canSendMedia: Boolean = true, style: ComposeStyle = ComposeStyle.INLINE) {
        rule.setContent {
            PhotonTheme {
                ChatThread(
                    title = PreviewSamples.LONG_DM_TITLE,
                    platform = Platform.WHATSAPP,
                    messages = PreviewSamples.dmMessages,
                    isGroup = false,
                    participantNames = emptyMap(),
                    onSendText = { _, _ -> },
                    onSendAudio = if (canSendMedia) { _, _ -> } else null,
                    onSendMedia = if (canSendMedia) { path, mime, _ -> sentMedia += path to mime } else null,
                    onBack = {},
                    onMediaTap = null,
                    onMessagesLoaded = null,
                    onReact = null,
                    onRetry = null,
                    onDelete = null,
                    onTitleClick = null,
                    supportsReply = true,
                    headerContent = null,
                    composeStyle = style,
                    keyboardStyle = KeyboardStyle.SYSTEM,
                )
            }
        }
    }

    private fun described(description: String) = rule.onNodeWithContentDescription(description)

    private fun count(description: String) =
        rule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size

    private fun openSheet() {
        described("Attach").performClick()
        rule.waitForIdle()
    }

    private fun sheetOpen() = count("Close") > 0

    private fun lastStarted(): Intent = assertNotNull(shadowOf(rule.activity).nextStartedActivityForResult).intent

    @Test
    fun withACameraAppTheSheetOffersCameraPhotosAndFiles() {
        installCameraApp()
        showDm()
        openSheet()
        listOf("Camera", "Photos", "Files", "Close").forEach { described(it).assertExists() }
        // Voice notes stay a top-level control, not part of the sheet.
        rule.onAllNodesWithContentDescription("Voice note").assertCountEquals(1)
    }

    @Test
    fun withoutACameraAppThereIsNoCamera() {
        showDm()
        openSheet()
        rule.onAllNodesWithContentDescription("Camera").assertCountEquals(0)
        described("Photos").assertExists()
        described("Files").assertExists()
    }

    @Test
    fun aPlatformThatCantSendMediaHasNoAttach() {
        installCameraApp()
        showDm(canSendMedia = false)
        rule.onAllNodesWithContentDescription("Attach").assertCountEquals(0)
    }

    @Test
    fun theChevronAndBackCloseTheSheet() {
        showDm()
        openSheet()
        described("Close").performClick()
        assertFalse(sheetOpen())

        openSheet()
        // The sheet's Back, over the thread's.
        assertEquals(2, count("Back"))
        rule.onAllNodesWithContentDescription("Back")[1].performClick()
        assertFalse(sheetOpen())

        openSheet()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertFalse(sheetOpen())
    }

    @Test
    fun fullScreenComposeOpensTheSameSheet() {
        showDm(style = ComposeStyle.FULL_SCREEN)
        openSheet()
        described("Photos").assertExists()
    }

    @Test
    fun photosOpensThePhotoPickerAndClosesTheSheet() {
        showDm()
        openSheet()
        described("Photos").performClick()
        assertEquals(MediaStore.ACTION_PICK_IMAGES, lastStarted().action)
        assertFalse(sheetOpen())
    }

    @Test
    fun filesOpensTheDocumentPickerForPhotosVideosAndDocuments() {
        showDm()
        openSheet()
        described("Files").performClick()
        val intent = lastStarted()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertContentEquals(ATTACHABLE_FILE_TYPES, intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertFalse(sheetOpen())
    }

    @Test
    fun cameraAsksForThePermissionFirst() {
        installCameraApp()
        showDm()
        openSheet()
        described("Camera").performClick()
        val asked = shadowOf(rule.activity).lastRequestedPermission
        assertNotNull(asked)
        assertTrue(Manifest.permission.CAMERA in asked.requestedPermissions)
    }

    /**
     * One test for both outcomes: FileProvider caches its roots statically,
     * and each Robolectric test has its own files directory.
     */
    @Test
    fun aTakenPhotoIsStagedAndSentAndACancelledOneDropped() {
        installCameraApp()
        shadowOf(rule.activity.application).grantPermissions(Manifest.permission.CAMERA)
        showDm()
        val capture = captureFile(rule.activity)

        fun takePhoto(): Intent {
            openSheet()
            described("Camera").performClick()
            return lastStarted()
        }

        // Cancelled: nothing is sent, and the camera's file goes.
        val cancelled = takePhoto()
        capture.writeBytes(byteArrayOf(1))
        shadowOf(rule.activity).receiveResult(cancelled, Activity.RESULT_CANCELED, null)
        rule.waitForIdle()
        assertTrue(sentMedia.isEmpty())
        assertFalse(capture.exists())

        // Taken: the camera app writes through Photon's FileProvider, then reports success.
        val request = takePhoto()
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, request.action)
        @Suppress("DEPRECATION")
        val output = assertNotNull(request.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT))
        assertEquals("content", output.scheme)
        assertEquals("${rule.activity.packageName}.camera", output.authority)
        capture.writeBytes(byteArrayOf(1, 2, 3))
        shadowOf(rule.activity).receiveResult(request, Activity.RESULT_OK, null)
        rule.waitForIdle()

        // Staged where the platforms send from, as a JPEG.
        val (path, mime) = sentMedia.single()
        val staged = File(path)
        assertEquals(rule.activity.filesDir, staged.parentFile)
        assertTrue(staged.name.matches(Regex("send_media_\\d+\\.jpg")), staged.name)
        assertContentEquals(byteArrayOf(1, 2, 3), staged.readBytes())
        assertEquals("image/jpeg", mime)
        assertFalse(capture.exists())
    }
}
