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
    // One notification per conversation, keyed by tag=conversationJid. Using a tag
    // (instead of a per-message incrementing id tracked in an in-memory map) means
    // cancellation still works after the process is restarted, and new messages in
    // the same chat replace the existing notification instead of stacking endlessly.
    private const val MESSAGE_NOTIF_ID = 1

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
            context,
            conversationJid?.hashCode() ?: 0,
            intent,
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
            NotificationManagerCompat.from(context).notify(
                conversationJid, MESSAGE_NOTIF_ID, notification,
            )
        } catch (_: SecurityException) {
            // Notification permission not granted
        }
    }

    fun cancelForConversation(context: Context, conversationJid: String) {
        NotificationManagerCompat.from(context).cancel(conversationJid, MESSAGE_NOTIF_ID)
    }
}
