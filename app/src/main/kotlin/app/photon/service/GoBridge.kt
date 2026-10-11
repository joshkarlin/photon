package app.photon.service

import android.content.Context
import android.util.Log
import app.photon.data.PhotonPreferences
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Owns the WhatsApp bridge process. Its stdin/stdout carry the line protocol
 * (see [BridgeClient]); its stderr is forwarded to logcat.
 */
class GoBridge(private val context: Context) {
    @Volatile private var process: Process? = null

    /**
     * Start the bridge unless it's already running, and return the process
     * (null if the binary is missing). Callers serialize start/stop.
     */
    suspend fun start(): Process? {
        process?.takeIf { it.isAlive }?.let { return it }

        // The Go binary is packaged as libgobridge.so in the native libs directory.
        // This directory is executable on Android (unlike filesDir or assets).
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val binary = File(nativeLibDir, "libgobridge.so")

        if (!binary.exists()) {
            Log.e("GoBridge", "Go bridge binary not found at ${binary.absolutePath}")
            return null
        }

        val dataDir = context.filesDir.absolutePath

        // Ensure media/thumbs dirs exist
        File(dataDir, "media").mkdirs()
        File(dataDir, "thumbs").mkdirs()

        val prefs = PhotonPreferences(context)
        val pb = ProcessBuilder(
            bridgeArgs(binary.absolutePath, dataDir, prefs.maxMessages.first(), prefs.maxDays.first()),
        )
            .directory(context.filesDir)

        // Go's pure-Go DNS resolver can't read Android's DNS config.
        // Force it to use Google DNS as a fallback.
        pb.environment()["GODEBUG"] = "netdns=go"
        pb.environment()["GOFLAGS"] = ""

        val started = pb.start()
        process = started

        thread(isDaemon = true, name = "gobridge-log") {
            try {
                started.errorStream.bufferedReader().forEachLine { line ->
                    Log.d("GoBridge", line)
                }
            } catch (_: Exception) {
                // Process was destroyed — expected during restart/reset
            }
        }

        Log.i("GoBridge", "Started Go bridge process from ${binary.absolutePath}")
        return started
    }

    /**
     * Stop the bridge. Closing its stdin lets it disconnect from WhatsApp and
     * exit on its own; it's killed if it hasn't within a couple of seconds.
     */
    fun stop() {
        val p = process ?: return
        process = null
        try { p.outputStream.close() } catch (_: Exception) {}
        try {
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroy()
                p.waitFor(1, TimeUnit.SECONDS)
            }
        } catch (_: InterruptedException) {
            p.destroy()
        }
        Log.i("GoBridge", "Stopped Go bridge process")
    }

    fun isRunning(): Boolean = process?.isAlive == true

    companion object {
        /** Downloaded media is deleted this long after it was fetched. */
        const val MEDIA_TTL_MINS = 5
    }
}

/**
 * The bridge's command line. Retention rides along so a restart keeps the
 * user's settings (the bridge otherwise falls back to 50 messages / 7 days
 * until the next set_retention).
 */
internal fun bridgeArgs(binary: String, dataDir: String, maxMessages: Int, maxDays: Int): List<String> =
    listOf(
        binary,
        "--data-dir", dataDir,
        "--max-messages", maxMessages.toString(),
        "--max-days", maxDays.toString(),
        "--media-ttl-mins", GoBridge.MEDIA_TTL_MINS.toString(),
    )

/**
 * Delay before restarting a bridge that exited: 1s, doubling to a 60s cap,
 * back to 1s once a bridge has stayed up.
 */
class RestartBackoff(private val initialMs: Long = 1_000, private val maxMs: Long = 60_000) {
    private var nextMs = initialMs

    fun next(): Long {
        val delay = nextMs
        nextMs = (nextMs * 2).coerceAtMost(maxMs)
        return delay
    }

    fun reset() {
        nextMs = initialMs
    }
}
