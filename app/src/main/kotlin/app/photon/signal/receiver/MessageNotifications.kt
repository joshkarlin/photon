package app.photon.signal.receiver

import app.photon.data.model.Message
import app.photon.service.messageNotificationText
import app.photon.service.notificationPreview
import app.photon.signal.SignalNames
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.SignalMessageDatabase.RemoteDeleteChange
import app.photon.signal.db.getConversation
import app.photon.signal.db.newestUnreadMessage

/** What a conversation's message notification should do after a delete-for-everyone. */
internal sealed interface AfterRemoteDelete {
    /** Nothing unread is left: clear it. */
    data object Cancel : AfterRemoteDelete

    /** A newer unread message is what the notification shows: leave it. */
    data object Keep : AfterRemoteDelete

    /**
     * The notification may show the deleted text: re-post it for [newest],
     * the newest message still unread, or with neutral text when only missed
     * calls are left unread.
     */
    data class Show(val newest: Message?) : AfterRemoteDelete
}

internal fun notificationAfterRemoteDelete(
    change: RemoteDeleteChange,
    newestUnread: () -> Message?,
): AfterRemoteDelete = when {
    change.unreadCount == 0 -> AfterRemoteDelete.Cancel
    change.newerUnread -> AfterRemoteDelete.Keep
    else -> AfterRemoteDelete.Show(newestUnread())
}

/** Signal message notifications: titles and previews, posted and corrected. */
internal class MessageNotifications(
    private val messageDb: SignalMessageDatabase,
    private val names: SignalNames,
    private val notifier: SignalNotifier,
) {
    /**
     * For groups, the title is the group name (with the sender's name woven
     * into the body); for DMs it's the sender.
     */
    fun showMessage(
        conversationJid: String,
        isGroup: Boolean,
        senderAci: String,
        text: String?,
        contentType: String,
    ) {
        val notification = messageNotificationText(
            conversationTitle = names.title(conversationJid, isGroup),
            isGroup = isGroup,
            senderName = if (isGroup) names.person(senderAci) else null,
            preview = notificationPreview(text, contentType),
        )
        notifier.showMessage(notification.title, notification.body, conversationJid)
    }

    /** A delete-for-everyone took an unread message out of the conversation. */
    fun afterRemoteDelete(change: RemoteDeleteChange) {
        val jid = change.conversationJid
        when (val next = notificationAfterRemoteDelete(change) { messageDb.newestUnreadMessage(jid) }) {
            AfterRemoteDelete.Cancel -> notifier.cancelConversation(jid)
            AfterRemoteDelete.Keep -> {}
            is AfterRemoteDelete.Show -> {
                // A dismissed notification stays dismissed.
                if (!notifier.isShowingMessage(jid)) return
                val conversation = messageDb.getConversation(jid)
                val isGroup = conversation?.isGroup == true
                val newest = next.newest
                if (newest != null) {
                    showMessage(jid, isGroup, newest.senderJid, newest.textBody, newest.contentType)
                } else {
                    notifier.showMessage(names.title(jid, isGroup), null, jid)
                }
            }
        }
    }
}
