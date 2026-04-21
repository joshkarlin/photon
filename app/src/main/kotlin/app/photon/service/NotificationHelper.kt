package app.photon.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.photon.MainActivity
import app.photon.PhotonApp
import app.photon.data.PhotonPreferences

object NotificationHelper {
    private var nextId = 100
    private val conversationNotifIds = mutableMapOf<String, MutableList<Int>>()

    fun showMessageNotification(
        context: Context,
        senderName: String,
        body: String?,
        platform: String,
        conversationJid: String? = null,
    ) {
        val prefs = PhotonPreferences(context)
        if (!prefs.getNotificationsEnabledSync()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, nextId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = "$senderName · $platform"
        val text = body ?: "New message"

        val notification = NotificationCompat.Builder(context, PhotonApp.CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            val id = nextId++
            NotificationManagerCompat.from(context).notify(id, notification)
            if (conversationJid != null) {
                conversationNotifIds.getOrPut(conversationJid) { mutableListOf() }.add(id)
            }
        } catch (_: SecurityException) {
            // Notification permission not granted
        }
    }

    fun cancelForConversation(context: Context, conversationJid: String) {
        conversationNotifIds.remove(conversationJid)?.forEach {
            NotificationManagerCompat.from(context).cancel(it)
        }
    }
}
