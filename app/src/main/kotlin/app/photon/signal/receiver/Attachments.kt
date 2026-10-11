package app.photon.signal.receiver

import android.content.Context
import android.util.Log
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.clearMediaUrlByPath
import app.photon.signal.db.getMessage
import app.photon.signal.db.getMessageRawProto
import app.photon.signal.db.updateMediaUrl
import java.io.File
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver
import org.whispersystems.signalservice.api.crypto.AttachmentCipherInputStream
import org.whispersystems.signalservice.api.util.AttachmentPointerUtil
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.PushServiceSocket

private const val TAG = SignalMessageReceiver.TAG

// Cap on incoming media downloads (Signal's own attachment limit is
// ~100 MB).
private const val MAX_ATTACHMENT_BYTES: Long = 100L * 1024 * 1024

// Downloaded media TTL — matches the WhatsApp bridge's ephemeral
// policy (viewed, then auto-deleted; re-downloadable on tap while
// the CDN pointer is still valid).
private const val MEDIA_TTL_MS: Long = 5L * 60 * 1000

/** Incoming attachments: on-demand download from Signal's CDN and the media TTL. */
internal class Attachments(
    private val context: Context,
    private val messageDb: SignalMessageDatabase,
    private val pushSocket: () -> PushServiceSocket?,
) {
    /**
     * Download an incoming attachment by message id. The serialized
     * AttachmentPointer was stored in raw_proto at receive time; this fetches
     * and decrypts the blob from Signal's CDN, writes it under
     * filesDir/signal_media, and records the path on the message row so the
     * UI renders it directly next time. Returns the local path, or null when
     * the pointer is missing/expired (Signal CDN attachments expire after
     * ~45 days) or the download fails. Safe to call repeatedly — an existing
     * downloaded file short-circuits.
     */
    fun downloadAttachment(messageId: String): String? {
        val push = pushSocket() ?: run {
            Log.w(TAG, "downloadAttachment: pushSocket not initialised")
            return null
        }
        val row = messageDb.getMessage(messageId) ?: return null
        row.mediaUrl?.let { existing ->
            if (File(existing).exists()) return existing
        }
        val pointerBytes = messageDb.getMessageRawProto(messageId) ?: run {
            Log.w(TAG, "downloadAttachment: no stored pointer for $messageId")
            return null
        }
        return try {
            val proto = AttachmentPointer.ADAPTER.decode(pointerBytes)
            val pointer = AttachmentPointerUtil.createSignalAttachmentPointer(proto)
            val digest = pointer.digest.orElse(null) ?: run {
                Log.w(TAG, "downloadAttachment: pointer has no digest for $messageId")
                return null
            }
            val mediaDir = File(context.filesDir, "signal_media").apply { mkdirs() }
            val cipherFile = File(context.cacheDir, "sig_att_$messageId.bin")
            try {
                val messageReceiver = SignalServiceMessageReceiver(push)
                val plain = messageReceiver.retrieveAttachment(
                    pointer,
                    cipherFile,
                    MAX_ATTACHMENT_BYTES,
                    AttachmentCipherInputStream.IntegrityCheck.forEncryptedDigest(digest),
                )
                val outFile = File(mediaDir, messageId + extensionForMime(row.mediaMime))
                plain.use { input -> outFile.outputStream().use { input.copyTo(it) } }
                messageDb.updateMediaUrl(messageId, outFile.absolutePath)
                Log.i(TAG, "Downloaded attachment for $messageId (${outFile.length()}B)")
                outFile.absolutePath
            } finally {
                cipherFile.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Attachment download failed for $messageId: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /**
     * Delete downloaded Signal media older than the TTL and clear the
     * media_url on the affected rows (the stored AttachmentPointer keeps
     * tap-to-redownload working). Called periodically by the Signal session.
     */
    fun pruneMedia() {
        val mediaDir = File(context.filesDir, "signal_media")
        val cutoff = System.currentTimeMillis() - MEDIA_TTL_MS
        var pruned = 0
        mediaDir.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff && f.delete()) {
                messageDb.clearMediaUrlByPath(f.absolutePath)
                pruned++
            }
        }
        if (pruned > 0) Log.i(TAG, "Pruned $pruned downloaded Signal media files")
    }
}

/**
 * Map an attachment mime type to our content_type column vocabulary.
 */
internal fun contentTypeForMime(mime: String?): String = when {
    mime == null -> "document"
    mime.startsWith("image/") -> "image"
    mime.startsWith("video/") -> "video"
    mime.startsWith("audio/") -> "audio"
    else -> "document"
}

private fun extensionForMime(mime: String?): String = when {
    mime == null -> ""
    mime.startsWith("image/png") -> ".png"
    mime.startsWith("image/webp") -> ".webp"
    mime.startsWith("image/gif") -> ".gif"
    mime.startsWith("image/") -> ".jpg"
    mime.startsWith("video/") -> ".mp4"
    mime.startsWith("audio/") -> ".ogg"
    else -> ""
}
