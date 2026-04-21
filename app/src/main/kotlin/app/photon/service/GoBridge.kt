package app.photon.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

class GoBridge(private val context: Context) {
    private var process: Process? = null
    val port = 8765

    fun start() {
        if (process?.isAlive == true) return

        // The Go binary is packaged as libgobridge.so in the native libs directory.
        // This directory is executable on Android (unlike filesDir or assets).
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val binary = File(nativeLibDir, "libgobridge.so")

        if (!binary.exists()) {
            Log.e("GoBridge", "Go bridge binary not found at ${binary.absolutePath}")
            return
        }

        val dataDir = context.filesDir.absolutePath

        // Ensure media/thumbs dirs exist
        File(dataDir, "media").mkdirs()
        File(dataDir, "thumbs").mkdirs()

        val pb = ProcessBuilder(
            binary.absolutePath,
            "--data-dir", dataDir,
            "--ws-port", port.toString(),
        )
            .directory(context.filesDir)
            .redirectErrorStream(true)

        // Go's pure-Go DNS resolver can't read Android's DNS config.
        // Force it to use Google DNS as a fallback.
        pb.environment()["GODEBUG"] = "netdns=go"
        pb.environment()["GOFLAGS"] = ""

        process = pb.start()

        thread(isDaemon = true, name = "gobridge-log") {
            try {
                process?.inputStream?.bufferedReader()?.forEachLine { line ->
                    Log.d("GoBridge", line)
                }
            } catch (_: Exception) {
                // Process was destroyed — expected during restart/reset
            }
        }

        Log.i("GoBridge", "Started Go bridge process from ${binary.absolutePath}")
    }

    fun stop() {
        process?.destroy()
        process = null
        Log.i("GoBridge", "Stopped Go bridge process")
    }

    fun isRunning(): Boolean = process?.isAlive == true
}
