package app.photon.ui.shared.components

import android.content.ContentValues
import android.content.Context
import android.media.MediaPlayer
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
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
    // Only treat existingPath as usable if the file is actually still on disk.
    // Media gets pruned on a TTL while media_url stays set on the row, so a
    // stale path would otherwise skip the re-download and render a broken
    // fallback instead of just fetching the file again.
    val cachedPath = remember(existingPath) { existingPath?.takeIf { File(it).exists() } }
    var localPath by remember { mutableStateOf(cachedPath) }
    var loading by remember { mutableStateOf(cachedPath == null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Download on open (skip if already have a valid local file)
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

    BackHandler(onBack = onDismiss)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background)
            .lightClickable(hapticsEnabled = false, onClick = onDismiss),
    ) {
        LightTopBar(leftButton = backButton(onDismiss))

        // Content
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> LightText("Downloading…", variant = LightTextVariant.Paragraph, lighten = true)
                error != null -> LightText(error!!, variant = LightTextVariant.Paragraph)
                localPath != null -> {
                    val file = File(localPath!!)
                    when {
                        file.exists() && mediaMime?.startsWith("image/") == true -> {
                            AsyncImage(
                                model = file,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().padding(1f.gridUnitsAsDp()),
                            )
                        }
                        file.exists() && mediaMime?.startsWith("video/") == true -> {
                            VideoPlayer(file = file)
                        }
                        file.exists() && mediaMime?.startsWith("audio/") == true -> {
                            AudioPlayer(file = file)
                        }
                        else -> {
                            LightText("Downloaded: ${file.name}", variant = LightTextVariant.Paragraph)
                        }
                    }
                }
            }
        }

        if (localPath != null) {
            LightBottomBar(
                items = listOf(
                    LightBarButton.Text(
                        text = "SAVE TO DEVICE",
                        onClick = { scope.launch { saveToDevice(context, localPath!!, mediaMime) } },
                    ),
                ),
            )
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
        modifier = Modifier.fillMaxSize().padding(1f.gridUnitsAsDp()),
    )
}

@Composable
private fun AudioPlayer(file: File) {
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

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.5f.gridUnitsAsDp()),
    ) {
        LightText(
            text = "%d:%02d / %d:%02d".format(elapsed / 60, elapsed % 60, duration / 60, duration % 60),
            variant = LightTextVariant.Copy,
        )
        LightIcon(
            icon = if (playing) LightIcons.PAUSE else LightIcons.PLAY,
            contentDescription = if (playing) "Pause" else "Play",
            modifier = Modifier.lightClickable {
                if (playing) {
                    player.pause()
                    playing = false
                } else {
                    player.start()
                    playing = true
                }
            },
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
