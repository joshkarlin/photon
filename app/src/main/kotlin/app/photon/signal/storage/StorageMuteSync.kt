package app.photon.signal.storage

import android.util.Log
import app.photon.signal.SignalCredentials
import app.photon.signal.SignalGroupV2Manager
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.StorageMute
import app.photon.signal.db.applyStorageManifest
import app.photon.signal.db.forgetStorageManifestVersion
import app.photon.signal.db.getAciForPhone
import app.photon.signal.db.getContact
import app.photon.signal.db.storageManifestVersion
import app.photon.signal.db.storageRecordIds
import app.photon.signal.db.updateContactPhone
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.signal.core.models.ServiceId
import org.signal.core.models.storageservice.StorageKey
import org.whispersystems.signalservice.internal.storage.protos.ContactRecord

private const val TAG = SignalMessageReceiver.TAG

/**
 * Chat mutes from the primary's storage service, read-only: Photon never
 * writes a manifest or a record.
 *
 * The storage key comes from linking (the provisioning message's account
 * entropy pool) or, for a device linked before Photon kept it, from a Keys
 * sync the primary sends in answer to a KEYS request. With it, a read takes
 * the manifest only when its version has changed since the last read, then
 * only the contact and GroupV2 records that are new since then, and stores
 * each one's mute against the conversation it maps to.
 *
 * Reads run after a connect (the receiver's upkeep, at most every 10
 * minutes) and when the primary says storage changed (FetchLatest
 * STORAGE_MANIFEST), one at a time: a request during a read runs once more
 * after it, never in a loop.
 *
 * @param reader the storage service over the current connection; null while disconnected
 * @param requestKeys asks the primary for the keys; returns whether the request went out
 * @param groupJid a GroupV2 master key's conversation jid
 */
internal class StorageMuteSync(
    private val credentials: SignalCredentials,
    private val messageDb: SignalMessageDatabase,
    private val scope: CoroutineScope,
    private val reader: () -> StorageReader?,
    private val requestKeys: () -> Boolean,
    private val groupJid: (ByteArray) -> String? = { mk ->
        try { SignalGroupV2Manager.jidFromMasterKey(mk) } catch (_: Exception) { null }
    },
    private val keysRequestDelayMs: Long = KEYS_REQUEST_DELAY_MS,
) {
    /** What one read did, for logs and tests. */
    enum class Outcome { NO_KEY, DISCONNECTED, UP_TO_DATE, NO_MANIFEST, BAD_KEY, FAILED, UPDATED }

    private val running = AtomicBoolean(false)
    private val pending = AtomicBoolean(false)

    // Asked the primary for the keys since the key last changed. It answers
    // when it's next online, so one request per key is enough; asking again
    // only after a new key arrives means a key the primary keeps confirming
    // but that doesn't decrypt can't start a request/answer loop.
    private val keysRequested = AtomicBoolean(false)

    /** After a connect: read storage, or ask the primary for the key first. */
    fun onConnected() {
        if (credentials.storageKey == null) {
            scope.launch {
                delay(keysRequestDelayMs) // let the socket settle, as the contacts request does
                requestKeysOnce()
            }
        } else {
            requestRead()
        }
    }

    /** The primary changed storage (FetchLatest STORAGE_MANIFEST). */
    fun onStorageChanged() = requestRead()

    /** A Keys sync from the primary: keep a new storage key and read with it. */
    fun onKeys(accountEntropyPool: String?) {
        val key = storageKeyFrom(accountEntropyPool)
        if (key == null) {
            Log.w(TAG, "Keys sync carried no usable account entropy pool")
            return
        }
        if (key.contentEquals(credentials.storageKey)) return
        credentials.storageKey = key
        // The version Photon read last was under the old key.
        messageDb.forgetStorageManifestVersion()
        keysRequested.set(false)
        Log.i(TAG, "Stored the storage key from a Keys sync")
        requestRead()
    }

    /** The account was unlinked (Signal reset): a new link may need to ask again. */
    fun reset() {
        keysRequested.set(false)
        pending.set(false)
    }

    /** Run a read, or one more after the read in progress. */
    private fun requestRead() {
        pending.set(true)
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (pending.getAndSet(false)) {
                    try {
                        read()
                    } catch (e: Exception) {
                        Log.w(TAG, "Storage read failed: ${e.message}")
                    }
                }
            } finally {
                running.set(false)
            }
            // A request that landed between the last check and the reset above.
            if (pending.get()) requestRead()
        }
    }

    /** One read, blocking on the network. */
    internal fun read(): Outcome {
        val keyBytes = credentials.storageKey ?: run {
            requestKeysOnce()
            return Outcome.NO_KEY
        }
        val storage = reader() ?: return Outcome.DISCONNECTED
        val key = StorageKey(keyBytes)
        val manifest = when (val fetch = storage.manifest(key, messageDb.storageManifestVersion())) {
            is ManifestFetch.Changed -> fetch.manifest
            ManifestFetch.Unchanged -> return Outcome.UP_TO_DATE
            ManifestFetch.NotFound -> {
                Log.i(TAG, "Storage service has no manifest for this account yet")
                return Outcome.NO_MANIFEST
            }
            ManifestFetch.BadKey -> return badKey("manifest")
            is ManifestFetch.Failed -> {
                Log.w(TAG, "Storage manifest read failed: ${fetch.reason}")
                return Outcome.FAILED
            }
        }

        val wanted = manifest.storageIds.filter(::carriesMute).associateBy(::storageIdKey)
        val known = messageDb.storageRecordIds()
        val toRead = wanted.filterKeys { it !in known }.values.toList()
        val removed = known - wanted.keys
        val records = if (toRead.isEmpty()) {
            emptyList()
        } else {
            when (val fetch = storage.records(key, manifest, toRead)) {
                is RecordsFetch.Read -> fetch.records
                RecordsFetch.BadKey -> return badKey("records")
                is RecordsFetch.Failed -> {
                    Log.w(TAG, "Storage record read failed: ${fetch.reason}")
                    return Outcome.FAILED
                }
            }
        }

        val mutes = records.map { record ->
            storageMuteOf(record, ::contactJid, groupJid).also { mute ->
                record.proto.contact?.let { rememberPhoneOfUnresolved(mute, it) }
            }
        }
        messageDb.applyStorageManifest(manifest.version, removed, mutes)
        Log.i(
            TAG,
            "Storage manifest v${manifest.version}: read ${records.size} chat records, " +
                "${removed.size} gone, ${mutes.count { it.mutedUntil != 0L }} of the new ones muted",
        )
        return Outcome.UPDATED
    }

    private fun contactJid(contact: ContactRecord): String? =
        contactConversationJid(
            contact,
            phoneOf = { messageDb.getContact(it)?.phone },
            aciForPhone = { messageDb.getAciForPhone(it) },
        )

    /**
     * A muted contact known only by PNI or number: store their number under
     * that id, as an envelope from them would, so that once a contacts sync
     * maps the number to an ACI, the receiver's PNI merge moves the mute to
     * the ACI chat.
     */
    private fun rememberPhoneOfUnresolved(mute: StorageMute, contact: ContactRecord) {
        val jid = mute.jid ?: return
        val phone = contact.e164.takeIf { it.isNotBlank() } ?: return
        if (mute.mutedUntil == 0L || ServiceId.ACI.parseOrNull(jid) != null) return
        messageDb.updateContactPhone(jid, phone)
    }

    private fun badKey(what: String): Outcome {
        Log.w(TAG, "Storage key doesn't decrypt the $what; asking the primary for the current one")
        requestKeysOnce()
        return Outcome.BAD_KEY
    }

    private fun requestKeysOnce() {
        if (!keysRequested.compareAndSet(false, true)) return
        if (!requestKeys()) keysRequested.set(false) // didn't go out: the next connect asks again
    }

    companion object {
        const val KEYS_REQUEST_DELAY_MS = 1_500L
    }
}
