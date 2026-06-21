package app.photon.ui.shared.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import app.photon.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed class InputState {
    data object Default : InputState()
    data object Recording : InputState()
}

@Composable
fun MessageInputBar(
    onSendText: (String) -> Unit,
    onSendAudio: ((String) -> Unit)? = null,
    onSendMedia: ((String, String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var state by remember { mutableStateOf<InputState>(InputState.Default) }
    var text by remember { mutableStateOf("") }
    var keyboardVisible by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingPath by remember { mutableStateOf<String?>(null) }

    // Start a new recording into a fresh file (permission already granted).
    fun beginRecording() {
        val path = File(context.filesDir, "voice_${System.currentTimeMillis()}.ogg").absolutePath
        recordingPath = path
        recorder = startRecording(context, path)
        if (recorder != null) state = InputState.Recording
    }

    // Stop and release the active recorder.
    fun stopRecorder() {
        recorder?.stop()
        recorder?.release()
        recorder = null
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginRecording()
    }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val sendMedia = onSendMedia
        if (uri == null || sendMedia == null) return@rememberLauncherForActivityResult
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
        // Copy off the main thread (a multi-MB image would jank it) and never
        // fail silently: the old version just logged and returned, so a null
        // stream or empty copy looked to the user like the send vanished. On
        // any failure we toast and drop the staged file; on success we hand
        // the local path to the platform sender, which inserts the bubble.
        scope.launch {
            val outFile = File(context.filesDir, "send_media_${System.currentTimeMillis()}$ext")
            val ok = withContext(Dispatchers.IO) {
                try {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: return@withContext false
                    input.use { i -> outFile.outputStream().use { o -> i.copyTo(o) } }
                    outFile.exists() && outFile.length() > 0
                } catch (e: Exception) {
                    android.util.Log.e("MessageInput", "Failed to copy media", e)
                    false
                }
            }
            if (ok) {
                sendMedia(outFile.absolutePath, mime)
            } else {
                outFile.delete()
                Toast.makeText(context, "Couldn't attach that file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun doRecord() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        beginRecording()
    }

    // Timer for recording
    LaunchedEffect(state) {
        if (state is InputState.Recording) {
            recordingSeconds = 0
            while (true) {
                kotlinx.coroutines.delay(1000)
                recordingSeconds++
            }
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose { recorder?.release() }
    }

    HorizontalDivider(color = Color(0xFF1A1A1A))

    when (state) {
        is InputState.Default -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .height(48.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Attach media
                if (onSendMedia != null) {
                    Box(
                        modifier = Modifier
                            .height(48.dp)
                            .clickable { mediaPickerLauncher.launch(arrayOf("image/*", "video/*")) }
                            .padding(end = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+", fontSize = 18.sp, color = Color(0xFF666666))
                    }
                }

                // Text input (always visible)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (text.isNotBlank()) {
                            onSendText(text.trim())
                            text = ""
                        }
                    }),
                    decorationBox = { inner ->
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (text.isEmpty()) {
                                Text(
                                    "Message",
                                    fontSize = 15.sp,
                                    color = Color(0xFF444444),
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                )

                if (text.isNotBlank()) {
                    // Send text
                    Box(
                        modifier = Modifier
                            .height(48.dp)
                            .clickable {
                                onSendText(text.trim())
                                text = ""
                            }
                            .padding(start = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("SEND", fontSize = 13.sp, letterSpacing = 2.sp, color = Color.White)
                    }
                } else if (onSendAudio != null) {
                    // Voice note — only when the platform can send audio (SMS
                    // can't, so the mic is hidden rather than a dead control).
                    Box(
                        modifier = Modifier
                            .height(48.dp)
                            .clickable { doRecord() }
                            .padding(start = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_mic),
                            contentDescription = "Voice note",
                            tint = Color(0xFF666666),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // Keyboard toggle
                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .clickable {
                            if (keyboardVisible) {
                                keyboardController?.hide()
                                keyboardVisible = false
                            } else {
                                focusRequester.requestFocus()
                                keyboardController?.show()
                                keyboardVisible = true
                            }
                        }
                        .padding(start = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⌨", fontSize = 16.sp, color = Color(0xFF666666))
                }
            }
        }

        is InputState.Recording -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("●", fontSize = 14.sp, color = Color(0xFFCC4444))
                Text(
                    text = " ${recordingSeconds / 60}:%02d".format(recordingSeconds % 60),
                    fontSize = 14.sp,
                    color = Color.White,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                // Cancel
                Text(
                    text = "✕",
                    fontSize = 16.sp,
                    color = Color(0xFF666666),
                    modifier = Modifier
                        .clickable {
                            stopRecorder()
                            recordingPath?.let { File(it).delete() }
                            state = InputState.Default
                        }
                        .padding(horizontal = 8.dp),
                )
                // Send
                Text(
                    text = "SEND",
                    fontSize = 13.sp,
                    letterSpacing = 2.sp,
                    color = Color.White,
                    modifier = Modifier
                        .clickable {
                            stopRecorder()
                            val path = recordingPath
                            val file = path?.let { File(it) }
                            if (path != null && file != null && file.exists() && file.length() > 0) {
                                onSendAudio?.invoke(path)
                            }
                            state = InputState.Default
                        }
                        .padding(horizontal = 8.dp),
                )
            }
        }
    }
}

// ─── Audio recording ─────────────────────────────────────────────

private fun startRecording(context: Context, path: String): MediaRecorder? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) return null

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
