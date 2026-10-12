package app.photon.signal.receiver

import android.util.Log
import app.photon.signal.IncomingReaction
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.applyEdit
import app.photon.signal.db.markDeletedByPrefix
import app.photon.signal.db.removeReaction
import app.photon.signal.db.resolveMessagePrefix
import app.photon.signal.db.upsertReaction
import app.photon.signal.incomingReactionFor
import org.whispersystems.signalservice.internal.push.DataMessage

private const val TAG = SignalMessageReceiver.TAG

/**
 * Reactions, deletes-for-everyone and edits, from other people or from the
 * user's other devices. Each names its message by "{author}_{timestamp}",
 * which may be an edit's (resolveMessagePrefix).
 */
internal class RemoteEdits(
    private val messageDb: SignalMessageDatabase,
    private val messageNotifications: MessageNotifications,
) {
    fun applyReaction(reaction: DataMessage.Reaction, reactorAci: String, timestamp: Long) {
        when (val update = incomingReactionFor(reaction, fallbackAuthor = reactorAci)) {
            is IncomingReaction.Set ->
                messageDb.upsertReaction(messageDb.resolveMessagePrefix(update.targetPrefix), reactorAci, update.emoji, timestamp)
            is IncomingReaction.Remove ->
                messageDb.removeReaction(messageDb.resolveMessagePrefix(update.targetPrefix), reactorAci)
            null -> Log.d(TAG, "Ignoring reaction without a target or emoji")
        }
    }

    /**
     * Blank a message deleted for everyone. If it was still unread, the
     * delete also takes it off the unread count, and the notification,
     * which may show the deleted text, goes or moves to what is still unread.
     */
    fun applyRemoteDelete(targetPrefix: String) {
        messageDb.markDeletedByPrefix(messageDb.resolveMessagePrefix(targetPrefix))
            .forEach { messageNotifications.afterRemoteDelete(it) }
    }

    /**
     * [authorAci] edited their message sent at [targetTimestamp] in
     * [conversationJid]: [edit] is the new version, sent at its own
     * timestamp. The text changes in place; the message keeps its position,
     * read state and disappearing clock. With [notify], an unread message's
     * notification is corrected without alerting again. Returns whether it
     * applied.
     */
    fun applyEdit(authorAci: String, targetTimestamp: Long, edit: DataMessage, conversationJid: String, notify: Boolean): Boolean {
        val editTimestamp = edit.timestamp ?: return false
        val target = messageDb.resolveMessagePrefix("${authorAci}_$targetTimestamp")
        val applied = messageDb.applyEdit(target, "${authorAci}_$editTimestamp", conversationJid, edit.body)
        if (applied == null) {
            Log.i(TAG, "Edit of ${authorAci.take(8)}…_$targetTimestamp not applied (not stored, or not editable)")
            return false
        }
        if (notify && applied.unread) messageNotifications.afterEdit(applied.conversationJid, applied.rowId)
        return true
    }
}
