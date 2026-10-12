package app.photon.signal.receiver

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import app.photon.data.model.Platform
import app.photon.service.NotificationHelper

/**
 * The notifications the Signal receiver posts and clears. An interface so
 * the handlers can be tested without Android's notification manager.
 */
internal interface SignalNotifier {
    /** [update]: corrects the showing notification without alerting again (see NotificationHelper). */
    fun showMessage(title: String, body: String?, conversationJid: String, update: Boolean = false)

    /** Whether the conversation's message notification is still showing (not dismissed). */
    fun isShowingMessage(conversationJid: String): Boolean
    fun showIncomingCall(callerName: String, conversationJid: String, isVideo: Boolean)

    /** Replaces the conversation's ringing notification, if any. */
    fun showMissedCall(callerName: String, conversationJid: String, isVideo: Boolean)
    fun cancelIncomingCall(conversationJid: String)

    /** Clears the conversation's message and missed-call notifications. */
    fun cancelConversation(conversationJid: String)

    /** Someone started a group call: a call, at the Calls level, that alerts once. */
    fun showGroupCallStarted(groupTitle: String, starterName: String, conversationJid: String)
}

internal class AndroidSignalNotifier(private val context: Context) : SignalNotifier {
    override fun showMessage(title: String, body: String?, conversationJid: String, update: Boolean) =
        NotificationHelper.showMessageNotification(context, title, body, Platform.SIGNAL, conversationJid, update)

    // NotificationHelper tags notifications with the conversation jid and
    // gives message notifications the message category.
    override fun isShowingMessage(conversationJid: String): Boolean = try {
        context.getSystemService(NotificationManager::class.java).activeNotifications.any {
            it.tag == conversationJid && it.notification.category == Notification.CATEGORY_MESSAGE
        }
    } catch (_: Exception) {
        // Can't tell: assume it is, rather than risk leaving deleted text up.
        true
    }

    override fun showIncomingCall(callerName: String, conversationJid: String, isVideo: Boolean) =
        NotificationHelper.showIncomingCall(context, callerName, Platform.SIGNAL, conversationJid, isVideo)

    override fun showMissedCall(callerName: String, conversationJid: String, isVideo: Boolean) =
        NotificationHelper.showMissedCall(context, callerName, Platform.SIGNAL, conversationJid, isVideo)

    override fun cancelIncomingCall(conversationJid: String) =
        NotificationHelper.cancelIncomingCall(context, conversationJid)

    override fun cancelConversation(conversationJid: String) =
        NotificationHelper.cancelForConversation(context, conversationJid)

    override fun showGroupCallStarted(groupTitle: String, starterName: String, conversationJid: String) =
        NotificationHelper.showGroupCallStarted(context, groupTitle, starterName, Platform.SIGNAL, conversationJid)
}
