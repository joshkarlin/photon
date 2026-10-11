package app.photon.service

import android.content.Context
import android.util.Log
import app.photon.data.PhotonPreferences
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Owns the WhatsApp bridge process. Its stdin/stdout carry the line protocol
 * (see [BridgeClient]); its stderr is forwarded to logcat. [launch] starts a
 * process (null if the binary is missing); tests pass a fake.
 */
class GoBridge internal constructor(
    private val launch: suspend () -> Process?,
    private val exitGraceMs: Long = EXIT_GRACE_MS,
) {
    constructor(context: Context) : this({ launchBridge(context) })

    @Volatile private var process: Process? = null

    /**
     * Start a bridge and return its process, or null if the binary is
     * missing. Callers serialize start/stop and stop the previous bridge
     * first: two bridges must never share the WhatsApp session.
     */
    suspend fun start(): Process? {
        check(process?.isAlive != true) { "WhatsApp bridge already running" }
        val started = launch() ?: return null
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
        return started
    }

    /**
     * Stop the bridge and return once it has exited. Closing its stdin makes
     * it exit within about a second, whatever it's doing; one still running
     * after [exitGraceMs] is killed. Blocks for up to a few seconds, so keep
     * it off the main thread.
     */
    fun stop() {
        val p = process ?: return
        process = null
        try { p.outputStream.close() } catch (_: IOException) {}
        try {
            if (!p.waitFor(exitGraceMs, TimeUnit.MILLISECONDS)) {
                Log.w("GoBridge", "Bridge still running ${exitGraceMs}ms after stdin closed; killing it")
                p.destroyForcibly()
                p.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS)
            }
        } catch (_: InterruptedException) {
            p.destroyForcibly()
            Thread.currentThread().interrupt()
        }
        Log.i("GoBridge", "Stopped Go bridge process")
    }

    fun isRunning(): Boolean = process?.isAlive == true

    companion object {
        /** Downloaded media is deleted this long after it was fetched. */
        const val MEDIA_TTL_MINS = 5

        // The bridge gives whatsmeow's disconnect a second (shutdownGrace in
        // main.go); this leaves room for it to exit before it's killed.
        private const val EXIT_GRACE_MS = 1_500L

        // SIGKILL is immediate; this only waits for the kernel to reap it.
        private const val KILL_WAIT_MS = 1_000L
    }
}

private suspend fun launchBridge(context: Context): Process? {
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
    Log.i("GoBridge", "Started Go bridge process from ${binary.absolutePath}")
    return started
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
