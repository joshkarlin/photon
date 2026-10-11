package app.photon.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.photon.MainActivity
import app.photon.PhotonApp
import app.photon.data.NotificationLevel
import app.photon.data.PhotonPreferences
import app.photon.data.model.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object NotificationHelper {
    // Notifications are keyed by tag=conversationJid. Using a tag (instead of a
    // per-message incrementing id tracked in an in-memory map) means
    // cancellation still works after the process is restarted, and new messages
    // in the same chat replace the existing notification instead of stacking.
    // Each kind gets its own id so a call and a message in the same chat don't
    // replace each other.
    private const val MESSAGE_NOTIF_ID = 1
    private const val INCOMING_CALL_NOTIF_ID = 2
    private const val MISSED_CALL_NOTIF_ID = 3

    // A ringing notification outlives the call by a margin; the caller gives
    // up well before this, and the terminate event replaces it as "missed".
    private const val INCOMING_CALL_TIMEOUT_MS = 60_000L

    /** A message notification's body when there's nothing of the message to show. */
    const val NEW_MESSAGE = "New message"

    const val EXTRA_PLATFORM = "app.photon.extra.PLATFORM"
    const val EXTRA_CONVERSATION = "app.photon.extra.CONVERSATION"

    // The conversation currently resumed on screen. Message notifications for
    // it are suppressed: the user is already looking at it, and posting would
    // race the screen's own cancel-on-load.
    @Volatile private var visibleConversation: String? = null

    fun setVisibleConversation(conversationJid: String) {
        visibleConversation = conversationJid
    }

    fun clearVisibleConversation(conversationJid: String) {
        if (visibleConversation == conversationJid) visibleConversation = null
    }

    fun isVisible(conversationJid: String): Boolean = visibleConversation == conversationJid

    // The NOTIFICATIONS setting, cached so posting doesn't wait on DataStore.
    // Null while nothing keeps it current.
    @Volatile private var cachedLevel: NotificationLevel? = null

    /**
     * Keeps the cached NOTIFICATIONS level current from [levels] for the life
     * of [scope]. `PhotonApp` follows the stored setting at startup.
     */
    fun followLevel(levels: Flow<NotificationLevel>, scope: CoroutineScope): Job = scope.launch {
        try {
            levels.collect { cachedLevel = it }
        } finally {
            cachedLevel = null
        }
    }

    // Before the first value arrives (moments after startup, well before a
    // session can deliver anything), read the setting directly. Posting
    // happens off the main thread.
    private fun level(context: Context): NotificationLevel =
        cachedLevel ?: runBlocking { PhotonPreferences(context).notificationLevel.first() }

    /** The channel a level posts messages and calls to; null at [NotificationLevel.OFF]. */
    internal fun channelFor(level: NotificationLevel): String? = when (level) {
        NotificationLevel.SOUND -> PhotonApp.CHANNEL_MESSAGES
        NotificationLevel.VIBRATE -> PhotonApp.CHANNEL_MESSAGES_VIBRATE
        NotificationLevel.SILENT -> PhotonApp.CHANNEL_MESSAGES_SILENT
        NotificationLevel.OFF -> null
    }

    /**
     * Post (or replace) the conversation's message notification.
     *
     * @param update true when this corrects the notification already showing
     *   (e.g. a delete-for-everyone took away the message it showed) rather
     *   than announcing a new message: it then replaces it without sounding
     *   or buzzing again. A new message always alerts, even over an
     *   existing notification. With notifications OFF, a correction removes
     *   the notification instead, so it can't keep showing what it corrects.
     */
    fun showMessageNotification(
        context: Context,
        title: String,
        body: String?,
        platform: Platform,
        conversationJid: String,
        update: Boolean = false,
    ) {
        if (isVisible(conversationJid)) return
        val channel = channelFor(level(context))
        if (channel == null) {
            if (update) NotificationManagerCompat.from(context).cancel(conversationJid, MESSAGE_NOTIF_ID)
            return
        }
        post(
            context, conversationJid, MESSAGE_NOTIF_ID,
            builder(context, channel, platform, conversationJid)
                .setContentTitle("$title · ${platform.label}")
                .setContentText(body ?: NEW_MESSAGE)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setOnlyAlertOnce(update),
        )
    }

    /**
     * A call is ringing on the user's other devices. Photon can't answer it;
     * this tells the user someone is calling. Not suppressed for the visible
     * conversation — a ringing call is time-sensitive even mid-chat. Posted
     * at every level but OFF, so at SILENT it shows without ringing.
     */
    fun showIncomingCall(
        context: Context,
        callerName: String,
        platform: Platform,
        conversationJid: String,
        isVideo: Boolean,
    ) {
        val channel = channelFor(level(context)) ?: return
        post(
            context, conversationJid, INCOMING_CALL_NOTIF_ID,
            builder(context, channel, platform, conversationJid)
                .setContentTitle("$callerName · ${platform.label}")
                .setContentText(if (isVideo) "Incoming video call" else "Incoming voice call")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setTimeoutAfter(INCOMING_CALL_TIMEOUT_MS),
        )
    }

    /** Replaces any ringing notification for the conversation with a missed-call one. */
    fun showMissedCall(
        context: Context,
        callerName: String,
        platform: Platform,
        conversationJid: String,
        isVideo: Boolean,
    ) {
        cancelIncomingCall(context, conversationJid)
        if (isVisible(conversationJid)) return
        val channel = channelFor(level(context)) ?: return
        post(
            context, conversationJid, MISSED_CALL_NOTIF_ID,
            builder(context, channel, platform, conversationJid)
                .setContentTitle("$callerName · ${platform.label}")
                .setContentText(if (isVideo) "Missed video call" else "Missed voice call")
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL),
        )
    }

    /** The call was answered, declined, or cancelled elsewhere — stop showing it as ringing. */
    fun cancelIncomingCall(context: Context, conversationJid: String) {
        NotificationManagerCompat.from(context).cancel(conversationJid, INCOMING_CALL_NOTIF_ID)
    }

    /** The conversation has been read: clear its message and missed-call notifications. */
    fun cancelForConversation(context: Context, conversationJid: String) {
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(conversationJid, MESSAGE_NOTIF_ID)
        nm.cancel(conversationJid, MISSED_CALL_NOTIF_ID)
    }

    /**
     * Clear message and missed-call notifications for every listed conversation
     * whose unread count is zero. Catches notifications left behind by any path
     * that cleared unread state without cancelling (reads on another device,
     * deletes, a previous process). Conversations not in the map are untouched,
     * so a single platform's list can reconcile its own notifications. Matches
     * by tag and id, so it finds them on every level's channel.
     */
    fun reconcile(context: Context, unreadByConversation: Map<String, Int>) {
        val active = try {
            context.getSystemService(NotificationManager::class.java).activeNotifications
        } catch (_: Exception) {
            return
        }
        for (sbn in active) {
            val tag = sbn.tag ?: continue
            if (sbn.id != MESSAGE_NOTIF_ID && sbn.id != MISSED_CALL_NOTIF_ID) continue
            if (unreadByConversation[tag] == 0) {
                NotificationManagerCompat.from(context).cancel(tag, sbn.id)
            }
        }
    }

    private fun builder(
        context: Context,
        channel: String,
        platform: Platform,
        conversationJid: String,
    ): NotificationCompat.Builder {
        // Tapping opens the conversation itself, not just the app.
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PLATFORM, platform.name)
            putExtra(EXTRA_CONVERSATION, conversationJid)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            "${platform.name}:$conversationJid".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
    }

    private fun post(context: Context, tag: String, id: Int, builder: NotificationCompat.Builder) {
        try {
            NotificationManagerCompat.from(context).notify(tag, id, builder.build())
        } catch (_: SecurityException) {
            // Notification permission not granted
        }
    }
}
