package app.photon.signal.receiver

import app.photon.data.model.Message
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
    private val contactNames: ContactNames,
    private val groupMetadata: GroupMetadata,
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
        senderName: String?,
        text: String?,
        contentType: String,
    ) {
        val title = if (isGroup) {
            groupMetadata.groupTitle(conversationJid)
        } else {
            senderName ?: senderAci.take(8)
        }
        val preview = text ?: "[$contentType]"
        val body = if (isGroup && !senderName.isNullOrBlank()) "$senderName: $preview" else preview
        notifier.showMessage(title, body, conversationJid)
    }

    /** A delete-for-everyone took an unread message out of the conversation. */
    fun afterRemoteDelete(change: RemoteDeleteChange) {
        val jid = change.conversationJid
        when (val next = notificationAfterRemoteDelete(change) { messageDb.newestUnreadMessage(jid) }) {
            AfterRemoteDelete.Cancel -> notifier.cancelConversation(jid)
            AfterRemoteDelete.Keep -> {}
            is AfterRemoteDelete.Show -> {
                val conversation = messageDb.getConversation(jid)
                val isGroup = conversation?.isGroup == true
                val newest = next.newest
                if (newest != null) {
                    val senderName = contactNames.displayNameFor(newest.senderJid, null)
                    showMessage(jid, isGroup, newest.senderJid, senderName, newest.textBody, newest.contentType)
                } else {
                    val title = if (isGroup) {
                        groupMetadata.groupTitle(jid)
                    } else {
                        conversation?.name?.takeIf { it.isNotBlank() } ?: jid.take(8)
                    }
                    notifier.showMessage(title, null, jid)
                }
            }
        }
    }
}
