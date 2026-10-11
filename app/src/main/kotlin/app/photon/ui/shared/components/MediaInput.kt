package app.photon.ui.shared.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Attaching media and recording voice notes for one chat, shared by the
 * inline input bar and the full-screen thread's bottom bar. Create it with
 * [rememberMediaInput].
 */
@Stable
class MediaInput internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sendAudio: State<((path: String) -> Unit)?>,
    private val sendMedia: State<((path: String, mime: String) -> Unit)?>,
) {
    internal lateinit var micPermission: ActivityResultLauncher<String>
    internal lateinit var picker: ActivityResultLauncher<Array<String>>

    /** Whether the platform can send images and videos. */
    val canAttach: Boolean get() = sendMedia.value != null

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

    /** Opens the system picker for an image or video, then sends it. */
    fun attach() {
        if (canAttach) picker.launch(arrayOf("image/*", "video/*"))
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
     * Copies a picked file into app storage and hands its path to the
     * platform sender, which inserts the bubble. Copies off the main thread
     * (a multi-MB image would jank it) and never fails silently: on any
     * failure it toasts and drops the staged file.
     */
    internal fun stageAndSend(uri: Uri) {
        val send = sendMedia.value ?: return
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val ext = when {
            mime.startsWith("image/jpeg") -> ".jpg"
            mime.startsWith("image/png") -> ".png"
            mime.startsWith("image/webp") -> ".webp"
            mime.startsWith("video/mp4") -> ".mp4"
            mime.startsWith("video/") -> ".mp4"
            mime.startsWith("image/") -> ".jpg"
            else -> ".bin"
        }
        scope.launch {
            val outFile = File(context.filesDir, "send_media_${System.currentTimeMillis()}$ext")
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
                send(outFile.absolutePath, mime)
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
    onSendMedia: ((path: String, mime: String) -> Unit)?,
): MediaInput {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sendAudio = rememberUpdatedState(onSendAudio)
    val sendMedia = rememberUpdatedState(onSendMedia)
    val media = remember(context, scope) { MediaInput(context, scope, sendAudio, sendMedia) }
    // Remembered launchers: the same instances on every composition.
    media.micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) media.beginRecording() }
    media.picker = rememberLauncherForActivityResult(
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
