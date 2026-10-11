package app.photon.signal.receiver

import android.util.Log
import app.photon.signal.AndroidContactResolver
import app.photon.signal.ConversationNaming
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.SignalProfileFetcher
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.getAciForPhone
import app.photon.signal.db.getContact
import app.photon.signal.db.getContactsWithProfileKeyButNoName
import app.photon.signal.db.getResolvableNonAciIds
import app.photon.signal.db.mergeServiceId
import app.photon.signal.db.reresolveConversationNames
import app.photon.signal.db.updateContactPhone
import app.photon.signal.db.upsertConversation
import org.signal.core.models.ServiceId

private const val TAG = SignalMessageReceiver.TAG

/**
 * Naming and profile resolution for Signal contacts: maps service ids to the
 * ACI a conversation is keyed by, picks display names (local contacts, then
 * Signal profile, then phone) and fetches profiles.
 */
internal class ContactNames(
    private val messageDb: SignalMessageDatabase,
    private val contactResolver: AndroidContactResolver,
    private val notifier: SignalNotifier,
    private val profileFetcher: () -> SignalProfileFetcher?,
    // After a merge: renamed call rows need their ring-expiry timers again.
    private val onMerged: () -> Unit = {},
) {
    /**
     * After (re)connecting, sweep for contacts that have a profile key
     * but no resolved name — earlier fetches may have failed silently
     * (e.g. because this very WS was never connected before today). Also
     * backfill conversation names using the Android contacts provider for
     * anyone whose Signal profile fetch returns null name.
     */
    fun sweepProfiles() {
        try {
            val contacts = messageDb.getContactsWithProfileKeyButNoName()
            if (contacts.isNotEmpty()) {
                Log.i(TAG, "Profile sweep: refetching ${contacts.size} contacts")
                contacts.forEach { c ->
                    profileFetcher()?.fetchAsync(c.jid, c.profileKey!!) { name ->
                        if (!name.isNullOrBlank()) {
                            messageDb.upsertConversation(c.jid, name, false)
                        } else {
                            // Fall back to Android contacts by phone number.
                            c.phone?.let { phone ->
                                contactResolver.resolve(phone)?.let { androidName ->
                                    Log.i(TAG, "Backfilled ${c.jid} from Android contacts: $androidName")
                                    messageDb.upsertConversation(c.jid, androidName, false)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Profile sweep failed: ${e.message}")
        }
    }

    /** Re-resolve every conversation title from this device's local contacts. Returns how many changed. */
    fun reresolveFromLocalContacts(): Int {
        contactResolver.invalidate()
        return messageDb.reresolveConversationNames { phone ->
            contactResolver.resolve(phone)
        }
    }

    /**
     * Conversations stored under a PNI or phone number (see
     * [normalizeServiceIdForConversation]) whose phone now maps to an ACI:
     * merge each into its ACI conversation. Run at start and after a
     * contacts sync.
     */
    fun mergeResolvableServiceIds() {
        for ((stored, aci) in messageDb.getResolvableNonAciIds()) mergeIntoAci(stored, aci)
    }

    private fun mergeIntoAci(stored: String, aci: String) {
        if (!messageDb.mergeServiceId(stored, aci)) return
        Log.i(TAG, "Merged conversation ${stored.take(12)}… into ACI ${aci.take(8)}…")
        // These would open a conversation that no longer exists.
        notifier.cancelIncomingCall(stored)
        notifier.cancelConversation(stored)
        onMerged()
    }

    /**
     * Conversation rows must key Signal contacts by ACI. Some envelopes can
     * identify the sender/destination by PNI; when that happens, use the E164
     * carried with the envelope/sync transcript to find the contact-sync ACI,
     * and merge anything already stored under the PNI into it. If the
     * mapping is not available yet, keep the raw id so the message is not
     * lost, and persist the phone mapping so a later contacts sync has enough
     * information to converge.
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

        val aci = e164?.let { messageDb.getAciForPhone(it) }
        if (aci != null) {
            Log.i(TAG, "$context: normalized non-ACI ${raw.take(8)}… to ACI ${aci.take(8)}… via $e164")
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
     * Friendly name for a Signal contact. Order: Android local contacts (this
     * device's address book) → Signal profile name (when it actually returns
     * one) → bare E.164. The envelope's E.164 is usually absent under sealed
     * sender, so fall back to the phone learned from contacts sync. Names from
     * the primary device's address book are deliberately never used.
     */
    fun displayNameFor(aci: String, e164: String?): String? {
        val contact = messageDb.getContact(aci)
        return ConversationNaming.resolveFromContact(
            phone = e164 ?: contact?.phone,
            profileName = contact?.profileName,
        ) { contactResolver.resolve(it) }
    }

    fun maybeFetchProfile(aci: String) {
        val fetcher = profileFetcher() ?: return
        val contact = messageDb.getContact(aci) ?: return
        if (contact.profileKey == null) return
        // Re-fetch only if we don't yet have a profile name or it's older than a day.
        val stale = System.currentTimeMillis() - contact.profileFetchedAt > 24 * 60 * 60 * 1000
        if (contact.profileName.isNullOrBlank() || stale) {
            fetcher.fetchAsync(aci, contact.profileKey) { resolvedName ->
                if (!resolvedName.isNullOrBlank()) {
                    messageDb.upsertConversation(
                        jid = aci,
                        name = resolvedName,
                        isGroup = false,
                    )
                }
            }
        }
    }
}
