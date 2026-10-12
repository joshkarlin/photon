package app.photon.signal.receiver

import android.content.Context
import android.util.Log
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.updateContactFromSync
import app.photon.signal.db.updateTimerFromContactSync
import java.io.File
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver
import org.whispersystems.signalservice.api.crypto.AttachmentCipherInputStream
import org.whispersystems.signalservice.api.messages.multidevice.DeviceContactsInputStream
import org.whispersystems.signalservice.api.util.AttachmentPointerUtil
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.PushServiceSocket

private const val TAG = SignalMessageReceiver.TAG

// Generous cap on the contacts sync attachment (~10 MB is way more
// than any plausible contact book).
private const val MAX_CONTACTS_BLOB_BYTES: Long = 50L * 1024 * 1024

/**
 * sync.contacts: the primary's contact book, ingested as each ACI's phone
 * number and address-book name (name sources for SignalNames).
 */
internal class ContactsSync(
    private val context: Context,
    private val messageDb: SignalMessageDatabase,
    private val pushSocket: () -> PushServiceSocket?,
    // Runs after the contact book is ingested: new phone mappings can merge
    // PNI-keyed conversations.
    private val onSynced: () -> Unit,
) {
    /**
     * Downloads, decrypts, and parses a sync.contacts attachment. Each
     * DeviceContact in the stream gets its name and phone stored on its
     * contact record; no conversation is created or renamed.
     */
    fun processContactsBlob(blob: AttachmentPointer) {
        val push = pushSocket()
        if (push == null) {
            Log.w(TAG, "processContactsBlob: pushSocket not initialised")
            return
        }
        val publicPointer = try {
            AttachmentPointerUtil.createSignalAttachmentPointer(blob)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build attachment pointer: ${e.message}")
            return
        }
        val digest = publicPointer.digest.orElse(null)
        if (digest == null) {
            Log.w(TAG, "Contacts blob has no digest, can't verify; skipping")
            return
        }
        val tempDir = File(context.cacheDir, "signal_sync").apply { mkdirs() }
        val cipherFile = File(tempDir, "contacts_${System.currentTimeMillis()}.bin")
        try {
            val messageReceiver = SignalServiceMessageReceiver(push)
            val plain = messageReceiver.retrieveAttachment(
                publicPointer,
                cipherFile,
                MAX_CONTACTS_BLOB_BYTES,
                AttachmentCipherInputStream.IntegrityCheck.forEncryptedDigest(digest),
            )
            val stream = DeviceContactsInputStream(plain)
            var count = 0
            while (true) {
                // Catch Throwable (not just Exception) — a malformed/truncated
                // stream can produce nonsense length prefixes that trigger
                // OutOfMemoryError when the parser tries to allocate a buffer.
                // OOM is Error not Exception, so a plain Exception catch lets
                // it escape and crashes the process.
                val contact = try {
                    stream.read()
                } catch (t: Throwable) {
                    Log.w(TAG, "DeviceContactsInputStream.read threw after $count contacts: ${t.javaClass.simpleName}: ${t.message}")
                    break
                } ?: break
                try {
                    ingestDeviceContact(contact)
                    count++
                } catch (t: Throwable) {
                    Log.w(TAG, "ingestDeviceContact threw: ${t.message}")
                }
                // DeviceContactsInputStream returns each contact with a
                // LimitedInputStream wrapping its avatar bytes. If we don't
                // drain it, the underlying stream is still pointing inside
                // the avatar payload when read() is called for the next
                // contact — and the parser will interpret avatar bytes as a
                // varint length, leading to OOM on the bogus allocation.
                contact.avatar.orElse(null)?.inputStream?.let { avatarStream ->
                    try {
                        val buf = ByteArray(8192)
                        while (avatarStream.read(buf) >= 0) { /* discard */ }
                    } catch (_: Throwable) { /* ignore */ }
                }
            }
            Log.i(TAG, "Contacts sync: ingested $count contacts")
            onSynced()
        } catch (t: Throwable) {
            Log.w(TAG, "Contacts sync download/parse failed: ${t.javaClass.simpleName}: ${t.message}", t)
        } finally {
            cipherFile.delete()
        }
    }

    /**
     * Store one synced contact: the ACI <-> phone mapping (which also lets the
     * LP3 address book name them) and the name the primary's address book
     * gives them. The LP3 contact still wins when there is one; this name
     * ranks above their Signal profile name.
     *
     * Also the chat's disappearing-messages timer and its version, as the
     * primary knows them. That's how Photon learns a timer it hasn't seen a
     * message carry (a chat untouched since the v7 upgrade, or one first
     * opened from New message), so its first send there can't turn the
     * timer off.
     */
    internal fun ingestDeviceContact(
        contact: org.whispersystems.signalservice.api.messages.multidevice.DeviceContact,
    ) {
        val aci = contact.aci.orElse(null)?.toString() ?: return
        messageDb.updateContactFromSync(aci, phone = contact.e164.orElse(null), name = contact.name.orElse(null))
        val timer = contact.expirationTimer.orElse(null)
        val version = contact.expirationTimerVersion.orElse(null)
        if (timer != null && version != null) messageDb.updateTimerFromContactSync(aci, timer.coerceAtLeast(0), version)
    }
}
