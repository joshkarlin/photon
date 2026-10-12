package app.photon.ui.shared.components

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Taking photos, attaching photos and files, and recording voice notes for
 * one chat. The attachment sheet ([AttachSheet]) offers the first three; the
 * inline bar and the full-screen thread's bottom bar record. Create it with
 * [rememberMediaInput].
 *
 * @param cameraAvailable whether an app can take a photo for Photon
 *   ([MediaStore.ACTION_IMAGE_CAPTURE]); the LP3's own camera may not
 */
@Stable
class MediaInput internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sendAudio: State<((path: String) -> Unit)?>,
    private val sendMedia: State<((path: String, mime: String, fileName: String?) -> Unit)?>,
    private val cameraAvailable: Boolean,
) {
    internal lateinit var micPermission: ActivityResultLauncher<String>
    internal lateinit var cameraPermission: ActivityResultLauncher<String>
    internal lateinit var camera: ActivityResultLauncher<Uri>
    internal lateinit var photoPicker: ActivityResultLauncher<PickVisualMediaRequest>
    internal lateinit var filePicker: ActivityResultLauncher<Array<String>>

    /**
     * Whether the platform can send photos, videos and files, so whether
     * there's an attachment sheet at all (SMS sends none).
     */
    val canAttach: Boolean get() = sendMedia.value != null

    /** Whether a photo can be taken and sent. */
    val canTakePhoto: Boolean get() = canAttach && cameraAvailable

    /** Whether the platform can send voice notes. */
    val canRecord: Boolean get() = sendAudio.value != null

    var isRecording by mutableStateOf(false)
        private set

    internal var recordingSeconds by mutableIntStateOf(0)

    /** "● 0:07" while recording. */
    val recordingLabel: String
        get() = "● ${recordingSeconds / 60}:%02d".format(recordingSeconds % 60)

    private var recorder: MediaRecorder? = null
    private var recordingPath: String? = null

    /** Opens Android's photo picker for a photo or video, then sends it. */
    fun pickPhoto() {
        if (canAttach) photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }

    /** Opens the system's document picker for a file ([ATTACHABLE_FILE_TYPES]), then sends it. */
    fun pickFile() {
        if (canAttach) filePicker.launch(ATTACHABLE_FILE_TYPES)
    }

    /**
     * Opens the camera app to take a photo, then sends it. Photon declares
     * CAMERA, so Android refuses the capture until it's granted: ask first.
     */
    fun takePhoto() {
        if (!canTakePhoto) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        openCamera()
    }

    /** Asks the camera app for a photo in [captureFile], through the FileProvider. */
    internal fun openCamera() {
        val file = captureFile(context).apply {
            parentFile?.mkdirs()
            delete()
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}$CAMERA_AUTHORITY_SUFFIX", file)
        try {
            camera.launch(uri)
        } catch (e: RuntimeException) {
            // No camera app after all, or one that refuses: say so.
            android.util.Log.w("MediaInput", "Couldn't open the camera", e)
            Toast.makeText(context, "Couldn't open the camera", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The camera app has finished. A photo it took is staged as a
     * send_media_* file, the only kind the platforms send, and sent;
     * anything else is dropped.
     */
    internal fun onPhotoTaken(taken: Boolean) {
        val capture = captureFile(context)
        val send = sendMedia.value
        if (!taken || send == null || capture.length() == 0L) {
            capture.delete()
            return
        }
        val staged = File(context.filesDir, "send_media_${System.currentTimeMillis()}.jpg")
        if (capture.renameTo(staged)) {
            send(staged.absolutePath, "image/jpeg", null)
        } else {
            capture.delete()
            Toast.makeText(context, "Couldn't attach that photo", Toast.LENGTH_SHORT).show()
        }
    }

    /** Starts a voice note, asking for the microphone first if needed. */
    fun record() {
        if (!canRecord || isRecording) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        beginRecording()
    }

    fun cancelRecording() {
        stopRecorder()
        recordingPath?.let { File(it).delete() }
        isRecording = false
    }

    fun sendRecording() {
        stopRecorder()
        val path = recordingPath
        val file = path?.let { File(it) }
        if (path != null && file != null && file.exists() && file.length() > 0) {
            sendAudio.value?.invoke(path)
        }
        isRecording = false
    }

    /** Starts a new recording into a fresh file (permission already granted). */
    internal fun beginRecording() {
        val path = File(context.filesDir, "voice_${System.currentTimeMillis()}.ogg").absolutePath
        recordingPath = path
        recorder = startRecording(context, path)
        if (recorder != null) isRecording = true
    }

    private fun stopRecorder() {
        recorder?.stop()
        recorder?.release()
        recorder = null
    }

    internal fun release() {
        recorder?.release()
        recorder = null
    }

    /**
     * Copies a picked file into app storage and hands its path, with the
     * file's own name, to the platform sender, which inserts the bubble.
     * Copies off the main thread (a multi-MB image would jank it) and never
     * fails silently: on any failure it toasts and drops the staged file.
     */
    internal fun stageAndSend(uri: Uri) {
        val send = sendMedia.value ?: return
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val ext = stagedExtension(mime)
        scope.launch {
            val outFile = File(context.filesDir, "send_media_${System.currentTimeMillis()}$ext")
            val name = withContext(Dispatchers.IO) { pickedFileName(context.contentResolver, uri) }
            val ok = withContext(Dispatchers.IO) {
                try {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: return@withContext false
                    input.use { i -> outFile.outputStream().use { o -> i.copyTo(o) } }
                    outFile.exists() && outFile.length() > 0
                } catch (e: Exception) {
                    android.util.Log.e("MediaInput", "Failed to copy media", e)
                    false
                }
            }
            if (ok) {
                send(outFile.absolutePath, mime, name)
            } else {
                outFile.delete()
                Toast.makeText(context, "Couldn't attach that file", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * A [MediaInput] for this chat. A null callback means the platform can't send
 * that kind of message (SMS sends neither), and its control is hidden.
 */
@Composable
fun rememberMediaInput(
    onSendAudio: ((path: String) -> Unit)?,
    onSendMedia: ((path: String, mime: String, fileName: String?) -> Unit)?,
): MediaInput {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sendAudio = rememberUpdatedState(onSendAudio)
    val sendMedia = rememberUpdatedState(onSendMedia)
    val media = remember(context, scope) {
        val cameraAvailable = Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) != null
        MediaInput(context, scope, sendAudio, sendMedia, cameraAvailable)
    }
    // Remembered launchers: the same instances on every composition.
    media.micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) media.beginRecording() }
    media.cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) media.openCamera() }
    media.camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { taken -> media.onPhotoTaken(taken) }
    media.photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) media.stageAndSend(uri) }
    media.filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) media.stageAndSend(uri) }

    LaunchedEffect(media, media.isRecording) {
        if (media.isRecording) {
            media.recordingSeconds = 0
            while (true) {
                delay(1000)
                media.recordingSeconds++
            }
        }
    }
    DisposableEffect(media) {
        onDispose { media.release() }
    }
    return media
}

/**
 * What Files offers: photos, videos and documents, which WhatsApp and Signal
 * both send as attachments. Not audio: both would send any audio file as a
 * voice note, which is what the mic is for.
 */
internal val ATTACHABLE_FILE_TYPES = arrayOf("image/*", "video/*", "application/*", "text/*")

/** A staged file's extension, so the platform and the recipient can tell what it is. */
internal fun stagedExtension(mime: String): String = mimeToExt(mime) ?: when {
    mime.startsWith("image/") -> ".jpg"
    mime.startsWith("video/") -> ".mp4"
    else -> ".bin"
}

/**
 * A picked file's own name ([OpenableColumns.DISPLAY_NAME]), made safe to
 * send by [sanitizeFileName]; null when the provider gives none.
 */
internal fun pickedFileName(resolver: ContentResolver, uri: Uri): String? = try {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) sanitizeFileName(cursor.getString(0)) else null
    }
} catch (_: RuntimeException) {
    // A provider that refuses the query (SecurityException, an unsupported
    // column): the file still goes, without its name.
    null
}

/** The longest file name sent, in characters, extension included. */
internal const val MAX_FILE_NAME_LENGTH = 100

/** Longer than this, an extension is taken to be part of the name, and may be cut. */
private const val MAX_EXTENSION_LENGTH = 10

/**
 * [raw], a file name from another app, as safe to send and show: path
 * separators become "_", control characters and bidirectional overrides
 * (which can disguise an extension) go, and it's cut to
 * [MAX_FILE_NAME_LENGTH] characters, keeping its extension. Null when nothing
 * is left.
 */
internal fun sanitizeFileName(raw: String): String? {
    val cleaned = buildString {
        raw.codePoints().forEach { cp ->
            when {
                cp == '/'.code || cp == '\\'.code -> append('_')
                Character.getType(cp) == Character.CONTROL.toInt() -> {}
                cp in BIDI_CONTROLS -> {}
                cp == 0x2028 || cp == 0x2029 -> {} // line and paragraph separators
                else -> appendCodePoint(cp)
            }
        }
    }.trim()
    if (cleaned.all { it == '.' }) return null
    return truncateKeepingExtension(cleaned, MAX_FILE_NAME_LENGTH)
}

/** Unicode's bidirectional marks, embeddings, overrides and isolates. */
private val BIDI_CONTROLS = setOf(0x061C, 0x200E, 0x200F) + (0x202A..0x202E) + (0x2066..0x2069)

/** [name] cut to [max] code points, keeping a short extension and whole characters. */
private fun truncateKeepingExtension(name: String, max: Int): String {
    val length = name.codePointCount(0, name.length)
    if (length <= max) return name
    val dot = name.lastIndexOf('.')
    val ext = if (dot > 0) name.substring(dot) else ""
    val extLength = ext.codePointCount(0, ext.length)
    val keep = if (extLength in 2..MAX_EXTENSION_LENGTH + 1) ext else ""
    val stemLimit = max - keep.codePointCount(0, keep.length)
    val stem = name.substring(0, name.offsetByCodePoints(0, stemLimit)).trimEnd()
    return stem + keep
}

/** Appended to the package name: the FileProvider the camera writes through (AndroidManifest.xml). */
private const val CAMERA_AUTHORITY_SUFFIX = ".camera"

/**
 * Where the camera app writes the photo being taken, in the FileProvider's
 * one directory (res/xml/camera_paths.xml). One at a time, so one name.
 */
internal fun captureFile(context: Context): File = File(File(context.filesDir, "camera"), "photo.jpg")

private fun startRecording(context: Context, path: String): MediaRecorder? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED
    ) return null

    return try {
        @Suppress("DEPRECATION")
        MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.OGG)
            setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            setOutputFile(path)
            prepare()
            start()
        }
    } catch (e: Exception) {
        android.util.Log.e("VoiceNote", "startRecording failed", e)
        null
    }
}
