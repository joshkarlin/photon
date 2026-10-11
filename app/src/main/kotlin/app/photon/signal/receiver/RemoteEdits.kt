package app.photon.signal.receiver

import android.util.Log
import app.photon.signal.IncomingReaction
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.markDeletedByPrefix
import app.photon.signal.db.removeReaction
import app.photon.signal.db.upsertReaction
import app.photon.signal.incomingReactionFor
import org.whispersystems.signalservice.internal.push.DataMessage

private const val TAG = SignalMessageReceiver.TAG

/** Reactions and deletes-for-everyone, from other people or from the user's other devices. */
internal class RemoteEdits(
    private val messageDb: SignalMessageDatabase,
    private val messageNotifications: MessageNotifications,
) {
    fun applyReaction(reaction: DataMessage.Reaction, reactorAci: String, timestamp: Long) {
        when (val update = incomingReactionFor(reaction, fallbackAuthor = reactorAci)) {
            is IncomingReaction.Set -> messageDb.upsertReaction(update.targetPrefix, reactorAci, update.emoji, timestamp)
            is IncomingReaction.Remove -> messageDb.removeReaction(update.targetPrefix, reactorAci)
            null -> Log.d(TAG, "Ignoring reaction without a target or emoji")
        }
    }

    /**
     * Blank a message deleted for everyone. If it was still unread, the
     * delete also takes it off the unread count, and the notification,
     * which may show the deleted text, goes or moves to what is still unread.
     */
    fun applyRemoteDelete(targetPrefix: String) {
        messageDb.markDeletedByPrefix(targetPrefix).forEach { messageNotifications.afterRemoteDelete(it) }
    }
}
