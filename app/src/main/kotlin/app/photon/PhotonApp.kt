package app.photon

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class PhotonApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        cleanupLegacyWebView()
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

        val messageChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setShowBadge(true)
            enableVibration(true)
        }
        manager.createNotificationChannel(messageChannel)
    }

    companion object {
        const val CHANNEL_SERVICE = "photon_service_v3"
        const val CHANNEL_MESSAGES = "photon_messages"
    }
}
