package app.photon.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import app.photon.MainActivity
import app.photon.PhotonApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * The foreground service that keeps Photon connected. It builds one session
 * per platform ([WhatsAppSession], [SignalSession], [SmsSession]), starts
 * them, and publishes them to the UI through [Sessions]; each session owns
 * its own start, stop and reset.
 */
class PhotonService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var whatsapp: WhatsAppSession
    private lateinit var signal: SignalSession
    private lateinit var sms: SmsSession

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("Running"))

        whatsapp = WhatsAppSession(this, scope)
        signal = SignalSession(this, scope)
        sms = SmsSession(this, scope)
        Sessions.publish(whatsapp, signal, sms)

        signal.start()
        whatsapp.start()
        sms.start()

        // Reflect connection state in the (hidden by default) service notification.
        scope.launch {
            while (true) {
                updateNotification()
                delay(10_000)
            }
        }
        // Each platform prunes its own downloaded media; this covers the
        // files staged for sending, which both WhatsApp and Signal use.
        scope.launch {
            while (true) {
                delay(5 * 60_000)
                try {
                    pruneStagedSendFiles()
                } catch (e: Exception) {
                    Log.w(TAG, "Media prune failed: ${e.message}")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Sessions.clear()
        sms.stop()
        signal.stop()
        // Stopping waits for the bridge to exit; keep that off the main thread.
        val whatsapp = whatsapp
        thread(name = "gobridge-stop") { runBlocking { whatsapp.stop() } }
        scope.cancel()
    }

    /**
     * Outgoing media staged by MessageInput (voice notes, picked files) lands
     * in filesDir as voice_*.ogg / send_media_* and was never cleaned up by
     * either platform. A 24h TTL (vs the 5min viewed-media TTL) keeps
     * retry-from-file working for recent failed sends.
     */
    private fun pruneStagedSendFiles() {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        filesDir.listFiles()?.forEach { f ->
            if (f.isFile &&
                (f.name.startsWith("voice_") || f.name.startsWith("send_media_")) &&
                f.lastModified() < cutoff
            ) {
                f.delete()
            }
        }
    }

    private var lastNotifiedStatus: String? = null

    private fun updateNotification() {
        val parts = mutableListOf<String>()
        if (whatsapp.bridge.whatsappState.value == "connected") parts.add("WA ✓") else parts.add("WA ✗")
        if (signal.receiver.state.value == "connected") {
            parts.add("Signal ✓")
        } else if (signal.credentials.isRegistered()) {
            parts.add("Signal ✗")
        }
        val status = parts.joinToString("  ")
        // Skip the re-post when nothing changed — this runs on a 10s timer
        // and each notify() is binder/systemui work that keeps the device
        // from idling.
        if (status == lastNotifiedStatus) return
        lastNotifiedStatus = status
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, PhotonApp.CHANNEL_SERVICE)
            .setContentTitle("Photon")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private companion object {
        const val TAG = "PhotonService"
        const val NOTIFICATION_ID = 1
    }
}
