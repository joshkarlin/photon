package app.photon

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.photon.data.PhotonPreferences
import app.photon.data.names.DisplayNames
import app.photon.data.names.deviceCountry
import app.photon.service.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class PhotonApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        DisplayNames.defaultCountry = deviceCountry(this)
        createNotificationChannels()
        val prefs = PhotonPreferences(this)
        NotificationHelper.followLevels(prefs.messageLevel, prefs.callLevel, scope)
        cleanupLegacyWebView()
    }

    // Never called on a device; emulated runs (Robolectric) end each app here,
    // and its notification-level collector mustn't outlive it.
    override fun onTerminate() {
        scope.cancel()
        super.onTerminate()
    }

    /** Remove leftover WebView data from the v0.1 WebView-based approach. */
    private fun cleanupLegacyWebView() {
        val prefs = getSharedPreferences("photon", MODE_PRIVATE)
        if (prefs.getBoolean("webview_cleaned", false)) return
        try {
            // Clear any WebView cache/cookies/storage from the old approach
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.WebStorage.getInstance().deleteAllData()
            val webviewDir = java.io.File(dataDir, "app_webview")
            if (webviewDir.exists()) webviewDir.deleteRecursively()
        } catch (_: Exception) {}
        prefs.edit().putBoolean("webview_cleaned", true).apply()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        // IMPORTANCE_NONE suppresses the ongoing foreground-service notification
        // from the shade by default, so launchers (e.g. inkOS) don't show an
        // unread asterisk on the app icon. The service still runs; the
        // notification is still built for startForeground but stays hidden. A
        // user who wants the live WA/Signal status can re-enable this channel in
        // Android settings. New channel id because importance is immutable once a
        // channel exists — the old _v2 (MIN) is removed below.
        manager.deleteNotificationChannel("photon_service_v2")
        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "Background Service",
            NotificationManager.IMPORTANCE_NONE,
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(serviceChannel)

        // One channel per level for messages and one for calls, as a
        // channel's sound, vibration and importance can't change once it
        // exists. A notification posts to its level's channel
        // (NotificationHelper.channelFor, callChannelFor). Messages at SOUND
        // keeps the original id, so settings the user made to it carry over.
        val soundChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setShowBadge(true)
            enableVibration(true)
        }
        val vibrateChannel = NotificationChannel(
            CHANNEL_MESSAGES_VIBRATE,
            "Messages (vibrate)",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setShowBadge(true)
            setSound(null, null)
            enableVibration(true)
        }
        val silentChannel = NotificationChannel(
            CHANNEL_MESSAGES_SILENT,
            "Messages (silent)",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(true)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannels(listOf(soundChannel, vibrateChannel, silentChannel))

        // Calls: all high importance, so even a silent call shows heads-up
        // while it rings. Ringing repeats the channel's sound or vibration
        // (FLAG_INSISTENT) until the call ends.
        val callSound = NotificationChannel(
            CHANNEL_CALLS,
            "Calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setShowBadge(true)
            enableVibration(true)
        }
        val callVibrate = NotificationChannel(
            CHANNEL_CALLS_VIBRATE,
            "Calls (vibrate)",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setShowBadge(true)
            setSound(null, null)
            enableVibration(true)
        }
        val callSilent = NotificationChannel(
            CHANNEL_CALLS_SILENT,
            "Calls (silent)",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setShowBadge(true)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannels(listOf(callSound, callVibrate, callSilent))
    }

    companion object {
        const val CHANNEL_SERVICE = "photon_service_v3"

        /** Messages at SOUND. */
        const val CHANNEL_MESSAGES = "photon_messages"

        /** Messages at VIBRATE. */
        const val CHANNEL_MESSAGES_VIBRATE = "photon_messages_vibrate"

        /** Messages at SILENT. */
        const val CHANNEL_MESSAGES_SILENT = "photon_messages_silent"

        /** Ringing, missed and group calls at SOUND. */
        const val CHANNEL_CALLS = "photon_calls"

        /** Ringing, missed and group calls at VIBRATE. */
        const val CHANNEL_CALLS_VIBRATE = "photon_calls_vibrate"

        /** Ringing, missed and group calls at SILENT. */
        const val CHANNEL_CALLS_SILENT = "photon_calls_silent"
    }
}
