package app.photon.ui.shared.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.core.content.ContextCompat
import app.photon.ui.theme.PhotonColors
import app.photon.ui.theme.lightTextStyle
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
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

    val barModifier = modifier
        .fillMaxWidth()
        .height(INPUT_BAR_HEIGHT.gridUnitsAsDp())
        .padding(horizontal = 1f.gridUnitsAsDp())
    val iconSpacing = Arrangement.spacedBy(1f.gridUnitsAsDp())
    val textStyle = lightTextStyle(LightTextVariant.Paragraph)
    val contentColor = LightThemeTokens.colors.content

    when (state) {
        is InputState.Default -> {
            Row(
                modifier = barModifier,
                horizontalArrangement = iconSpacing,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Attach media
                if (onSendMedia != null) {
                    InputIcon(LightIcons.ADD, "Attach") {
                        mediaPickerLauncher.launch(arrayOf("image/*", "video/*"))
                    }
                }

                // Text input (always visible)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = textStyle.copy(color = contentColor),
                    cursorBrush = SolidColor(contentColor),
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
                                LightText(
                                    text = "Message",
                                    variant = LightTextVariant.Paragraph,
                                    color = PhotonColors.Faint,
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
                    InputIcon(LightIcons.SEND, "Send") {
                        onSendText(text.trim())
                        text = ""
                    }
                } else if (onSendAudio != null) {
                    // Voice note — only when the platform can send audio (SMS
                    // can't, so the mic is hidden rather than a dead control).
                    InputIcon(LightIcons.MICROPHONE, "Voice note") { doRecord() }
                }

                // Keyboard toggle
                LightText(
                    text = "⌨",
                    variant = LightTextVariant.Paragraph,
                    lighten = true,
                    modifier = Modifier.lightClickable {
                        if (keyboardVisible) {
                            keyboardController?.hide()
                            keyboardVisible = false
                        } else {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                            keyboardVisible = true
                        }
                    },
                )
            }
        }

        is InputState.Recording -> {
            Row(
                modifier = barModifier,
                horizontalArrangement = iconSpacing,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightText(
                    text = "● ${recordingSeconds / 60}:%02d".format(recordingSeconds % 60),
                    variant = LightTextVariant.Paragraph,
                    modifier = Modifier.weight(1f),
                )
                InputIcon(LightIcons.CLOSE, "Cancel recording") {
                    stopRecorder()
                    recordingPath?.let { File(it).delete() }
                    state = InputState.Default
                }
                InputIcon(LightIcons.SEND, "Send voice note") {
                    stopRecorder()
                    val path = recordingPath
                    val file = path?.let { File(it) }
                    if (path != null && file != null && file.exists() && file.length() > 0) {
                        onSendAudio?.invoke(path)
                    }
                    state = InputState.Default
                }
            }
        }
    }
}

private const val INPUT_BAR_HEIGHT = 3.5f

@Composable
private fun InputIcon(icon: LightIconConfiguration, description: String, onClick: () -> Unit) {
    LightIcon(
        icon = icon,
        contentDescription = description,
        modifier = Modifier.lightClickable(onClick = onClick),
    )
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
