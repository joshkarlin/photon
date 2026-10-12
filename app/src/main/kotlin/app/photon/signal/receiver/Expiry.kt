package app.photon.signal.receiver

import android.util.Log
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.deleteExpiredMessages
import app.photon.signal.db.nextExpiry
import java.io.File

private const val TAG = SignalMessageReceiver.TAG

/**
 * Disappearing messages whose time is up: deleted with their reactions,
 * the media they downloaded or sent, and any notification still showing
 * them. SignalSession runs [sweep] whenever the next one is due.
 *
 * @param filesDir the app's files; media is only deleted from inside it
 */
internal class Expiry(
    private val messageDb: SignalMessageDatabase,
    private val messageNotifications: MessageNotifications,
    private val filesDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Delete what has expired; returns when the next message goes (epoch ms), or null when none is counting down. */
    fun sweep(): Long? {
        val expired = messageDb.deleteExpiredMessages(clock())
        if (expired.count > 0) {
            val files = expired.mediaPaths.count(::deleteMedia)
            expired.changes.forEach { messageNotifications.afterExpiry(it) }
            Log.i(TAG, "Disappearing messages: deleted ${expired.count} (and $files media files)")
        }
        return messageDb.nextExpiry()
    }

    /** Delete a row's media file, if it's one of Photon's own. */
    private fun deleteMedia(path: String): Boolean = try {
        val file = File(path).canonicalFile
        file.path.startsWith(filesDir.canonicalPath + File.separator) && file.isFile && file.delete()
    } catch (_: Exception) {
        false
    }
}
