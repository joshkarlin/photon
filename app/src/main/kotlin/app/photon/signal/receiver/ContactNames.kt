package app.photon.signal.receiver

import android.util.Log
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.SignalProfileFetcher
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.getAciForPhone
import app.photon.signal.db.getContact
import app.photon.signal.db.getContactsWithProfileKeyButNoName
import app.photon.signal.db.getResolvableNonAciIds
import app.photon.signal.db.mergeServiceId
import app.photon.signal.db.updateContactPhone
import org.signal.core.models.ServiceId

private const val TAG = SignalMessageReceiver.TAG

/**
 * Who Signal contacts are: maps service ids to the ACI a conversation is
 * keyed by, and fetches profiles. Names themselves are resolved when read
 * (SignalNames) from what this stores.
 */
internal class ContactNames(
    private val messageDb: SignalMessageDatabase,
    // Held while merging: the read loop holds it while processing an
    // envelope, so a merge can't land between two of its writes.
    private val storeLock: Any,
    private val notifier: SignalNotifier,
    private val profileFetcher: () -> SignalProfileFetcher?,
    // After a merge: renamed call rows need their ring-expiry timers again.
    private val onMerged: () -> Unit = {},
) {
    /**
     * After (re)connecting, sweep for contacts that have a profile key
     * but no profile name — earlier fetches may have failed silently
     * (e.g. because this very WS was never connected before today).
     */
    fun sweepProfiles() {
        try {
            val contacts = messageDb.getContactsWithProfileKeyButNoName()
            if (contacts.isNotEmpty()) {
                Log.i(TAG, "Profile sweep: refetching ${contacts.size} contacts")
                contacts.forEach { c -> profileFetcher()?.fetchAsync(c.jid, c.profileKey!!) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Profile sweep failed: ${e.message}")
        }
    }

    /**
     * Conversations stored under a PNI or phone number (see
     * [normalizeServiceIdForConversation]) whose phone now maps to an ACI:
     * merge each into its ACI conversation. Run at start and after a
     * contacts sync.
     */
    fun mergeResolvableServiceIds() = synchronized(storeLock) {
        for ((stored, aci) in messageDb.getResolvableNonAciIds()) mergeIntoAci(stored, aci)
    }

    private fun mergeIntoAci(stored: String, aci: String) {
        if (!synchronized(storeLock) { messageDb.mergeServiceId(stored, aci) }) return
        Log.i(TAG, "Merged conversation ${stored.take(12)}… into ACI ${aci.take(8)}…")
        // These would open a conversation that no longer exists.
        notifier.cancelIncomingCall(stored)
        notifier.cancelConversation(stored)
        onMerged()
    }

    /**
     * Conversation rows must key Signal contacts by ACI. Some envelopes can
     * identify the sender/destination by PNI; when that happens, use the E164
     * carried with the envelope/sync transcript (or the one stored for that
     * PNI before) to find the contact-sync ACI, and merge anything already
     * stored under the PNI into it. If the mapping is not available yet, keep
     * the raw id so the message is not lost, and persist the phone mapping so
     * a later contacts sync has enough information to converge.
     */
    fun normalizeServiceIdForConversation(
        serviceId: ServiceId,
        e164: String?,
        context: String,
    ): String {
        val raw = serviceId.toString()
        if (serviceId is ServiceId.ACI || ServiceId.ACI.parseOrNull(raw) != null) {
            return raw
        }

        val phone = e164 ?: messageDb.getContact(raw)?.phone
        val aci = phone?.let { messageDb.getAciForPhone(it) }
        if (aci != null) {
            Log.i(TAG, "$context: normalized non-ACI ${raw.take(8)}… to ACI ${aci.take(8)}… via $phone")
            mergeIntoAci(raw, aci)
            return aci
        }

        if (e164 != null) {
            messageDb.updateContactPhone(raw, e164)
        }
        Log.w(
            TAG,
            "$context: non-ACI service id ${raw.take(8)}… has no ACI mapping " +
                "(phone=${e164 ?: "none"}); using raw id until contacts sync resolves it",
        )
        return raw
    }

    fun normalizeServiceIdStringForConversation(
        serviceId: String?,
        e164: String?,
        context: String,
    ): String? {
        val parsed = serviceId?.let { ServiceId.parseOrNull(it) }
        if (parsed != null) return normalizeServiceIdForConversation(parsed, e164, context)

        val aci = e164?.let { messageDb.getAciForPhone(it) }
        if (aci != null) {
            Log.i(TAG, "$context: resolved missing/invalid service id to ACI ${aci.take(8)}… via $e164")
            if (!serviceId.isNullOrBlank()) mergeIntoAci(serviceId, aci)
            return aci
        }

        if (!serviceId.isNullOrBlank()) {
            Log.w(TAG, "$context: unparseable service id ${serviceId.take(8)}…")
            return serviceId
        }
        return null
    }

    /**
     * Refresh [aci]'s Signal profile name when it's missing or a day old. It
     * only ever updates the stored profile name (one source among several),
     * never a conversation's title.
     */
    fun maybeFetchProfile(aci: String) {
        val fetcher = profileFetcher() ?: return
        val contact = messageDb.getContact(aci) ?: return
        if (contact.profileKey == null) return
        // Re-fetch only if we don't yet have a profile name or it's older than a day.
        val stale = System.currentTimeMillis() - contact.profileFetchedAt > 24 * 60 * 60 * 1000
        if (contact.profileName.isNullOrBlank() || stale) {
            fetcher.fetchAsync(aci, contact.profileKey)
        }
    }
}
