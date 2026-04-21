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
        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "Background Service",
            NotificationManager.IMPORTANCE_MIN,
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
        const val CHANNEL_SERVICE = "photon_service_v2"
        const val CHANNEL_MESSAGES = "photon_messages"
    }
}
