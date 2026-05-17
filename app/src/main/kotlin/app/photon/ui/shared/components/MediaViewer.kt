package app.photon.ui.shared.components

import android.content.ContentValues
import android.content.Context
import android.media.MediaPlayer
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun MediaViewer(
    messageId: String,
    mediaMime: String?,
    existingPath: String? = null,
    onDownloadMedia: suspend (String) -> String?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var localPath by remember { mutableStateOf(existingPath) }
    var loading by remember { mutableStateOf(existingPath == null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Download on open (skip if already have local path)
    LaunchedEffect(messageId) {
        if (localPath != null) return@LaunchedEffect
        try {
            localPath = onDownloadMedia(messageId)
            if (localPath == null) error = "Download not available"
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            // Close bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 16.dp, bottom = 12.dp),
            ) {
                Text("<", fontSize = 18.sp, color = Color(0xFF666666),
                    modifier = Modifier.align(Alignment.CenterStart).clickable(onClick = onDismiss).padding(end = 16.dp))
            }

            // Content
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    loading -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                    error != null -> Text(error!!, color = Color(0xFFCC4444), fontSize = 14.sp)
                    localPath != null -> {
                        val file = File(localPath!!)
                        when {
                            file.exists() && mediaMime?.startsWith("image/") == true -> {
                                AsyncImage(
                                    model = file,
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize().padding(16.dp),
                                )
                            }
                            file.exists() && mediaMime?.startsWith("video/") == true -> {
                                VideoPlayer(file = file)
                            }
                            file.exists() && mediaMime?.startsWith("audio/") == true -> {
                                AudioPlayer(file = file, onDismiss = onDismiss)
                            }
                            else -> {
                                Text(
                                    text = "Downloaded: ${file.name}",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                }
            }

            // Save button
            if (localPath != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "SAVE TO DEVICE",
                    fontSize = 16.sp,
                    letterSpacing = 2.sp,
                    color = Color.White,
                    modifier = Modifier
                        .clickable {
                            scope.launch {
                                saveToDevice(context, localPath!!, mediaMime)
                            }
                        }
                        .padding(vertical = 20.dp),
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun VideoPlayer(file: File) {
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoPath(file.absolutePath)
                setOnPreparedListener { mp ->
                    mp.isLooping = false
                    start()
                }
            }
        },
        modifier = Modifier.fillMaxSize().padding(16.dp),
    )
}

@Composable
private fun AudioPlayer(file: File, onDismiss: () -> Unit) {
    var playing by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    val player = remember { MediaPlayer() }

    DisposableEffect(file) {
        try {
            player.setDataSource(file.absolutePath)
            player.prepare()
            duration = player.duration / 1000
            player.setOnCompletionListener {
                playing = false
                elapsed = 0
                player.seekTo(0)
            }
            // Voice notes are short and almost always intended to be heard
            // immediately — autoplay matches every other messaging app and
            // saves a tap. User can still STOP via the button below.
            player.start()
            playing = true
        } catch (e: Exception) {
            android.util.Log.e("AudioPlayer", "Failed to prepare", e)
        }
        onDispose {
            player.release()
        }
    }

    // Timer
    LaunchedEffect(playing) {
        if (playing) {
            while (playing) {
                elapsed = player.currentPosition / 1000
                delay(200)
            }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "%d:%02d / %d:%02d".format(elapsed / 60, elapsed % 60, duration / 60, duration % 60),
            fontSize = 18.sp,
            letterSpacing = 2.sp,
            color = Color.White,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = if (playing) "STOP" else "PLAY",
            fontSize = 18.sp,
            letterSpacing = 3.sp,
            color = Color.White,
            modifier = Modifier
                .clickable {
                    if (playing) {
                        player.pause()
                        playing = false
                    } else {
                        player.start()
                        playing = true
                    }
                }
                .padding(16.dp),
        )
    }
}

private fun saveToDevice(context: Context, path: String, mimeType: String?) {
    val file = File(path)
    if (!file.exists()) return

    val mime = mimeType ?: "application/octet-stream"
    val isImage = mime.startsWith("image/")
    val isVideo = mime.startsWith("video/")

    val collection = when {
        isImage -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
    }

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, when {
            isImage -> Environment.DIRECTORY_PICTURES + "/Photon"
            isVideo -> Environment.DIRECTORY_MOVIES + "/Photon"
            else -> Environment.DIRECTORY_DOWNLOADS + "/Photon"
        })
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(collection, values)
    if (uri != null) {
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
    }
}
