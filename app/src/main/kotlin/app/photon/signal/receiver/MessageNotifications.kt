package app.photon.signal.receiver

import app.photon.data.model.ContentTypes
import app.photon.data.model.Message
import app.photon.service.messageNotificationText
import app.photon.service.notificationPreview
import app.photon.signal.SignalNames
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.SignalMessageDatabase.RemoteDeleteChange
import app.photon.signal.db.getConversation
import app.photon.signal.db.isConversationMuted
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

/**
 * What a Signal message's notification says of it: "view-once photo" for
 * view-once media (whose row names only the kind), else the shared preview.
 */
internal fun signalNotificationPreview(text: String?, contentType: String): String? = when (contentType) {
    ContentTypes.VIEW_ONCE -> "view-once ${text?.takeIf { it.isNotBlank() } ?: "photo"}"
    else -> notificationPreview(text, contentType)
}

/**
 * Signal message notifications: titles and previews, posted and corrected.
 * A chat muted on the primary (storage service) posts none; its messages
 * still count as unread. An archived chat notifies as any other.
 */
internal class MessageNotifications(
    private val messageDb: SignalMessageDatabase,
    private val names: SignalNames,
    private val notifier: SignalNotifier,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * For groups, the title is the group name (with the sender's name woven
     * into the body); for DMs it's the sender. [update]: a correction of the
     * showing notification, which mustn't alert again.
     */
    fun showMessage(
        conversationJid: String,
        isGroup: Boolean,
        senderAci: String,
        text: String?,
        contentType: String,
        update: Boolean = false,
    ) {
        // A correction only replaces a notification that is already up.
        if (!update && messageDb.isConversationMuted(conversationJid, clock() / 1000)) return
        val notification = messageNotificationText(
            conversationTitle = names.title(conversationJid, isGroup),
            isGroup = isGroup,
            senderName = if (isGroup) names.person(senderAci) else null,
            preview = signalNotificationPreview(text, contentType),
        )
        notifier.showMessage(notification.title, notification.body, conversationJid, update)
    }

    /**
     * An unread message was edited: when it's the newest unread one, the
     * notification showing it is corrected, without alerting again.
     */
    fun afterEdit(conversationJid: String, editedRowId: String) {
        val newest = messageDb.newestUnreadMessage(conversationJid) ?: return
        if (newest.id != editedRowId || !notifier.isShowingMessage(conversationJid)) return
        val isGroup = messageDb.getConversation(conversationJid)?.isGroup == true
        showMessage(conversationJid, isGroup, newest.senderJid, newest.textBody, newest.contentType, update = true)
    }

    /**
     * Disappearing messages left the conversation (see Expiry): the same
     * correction as after a delete-for-everyone, so a notification never
     * keeps showing a message that has gone.
     */
    fun afterExpiry(change: RemoteDeleteChange) = afterRemoteDelete(change)

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
                // A correction, not a new message: no second alert.
                if (newest != null) {
                    showMessage(jid, isGroup, newest.senderJid, newest.textBody, newest.contentType, update = true)
                } else {
                    notifier.showMessage(names.title(jid, isGroup), null, jid, update = true)
                }
            }
        }
    }
}
