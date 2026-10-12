package app.photon.signal.receiver

import android.util.Log
import app.photon.signal.EarlyReadCache
import app.photon.signal.SignalCredentials
import app.photon.signal.SignalGroupV2Manager
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.applySignalVerificationSync
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.insertOwnRows
import app.photon.signal.db.isMessageStored
import app.photon.signal.db.markConversationRead
import app.photon.signal.db.markIncomingReadByPrefix
import app.photon.signal.db.resolveMessagePrefix
import app.photon.signal.db.updateContactPhone
import app.photon.signal.db.updateContactProfileKey
import app.photon.signal.db.updateDmTimer
import app.photon.signal.db.upsertConversation
import app.photon.signal.plannedRows
import app.photon.signal.replyPrefix
import app.photon.signal.serviceIdString
import app.photon.signal.storage.StorageMuteSync
import app.photon.signal.store.PhotonIdentityKeyStore
import app.photon.signal.syncedTimerUpdate
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.EditMessage
import org.whispersystems.signalservice.internal.push.SyncMessage

private const val TAG = SignalMessageReceiver.TAG

/**
 * SyncMessages from the user's other devices: sent transcripts (and edits),
 * reads, view-once opens, contacts, verification, calls, and the keys and
 * storage changes that chat mutes and archives come from
 * ([StorageMuteSync]).
 */
internal class SyncMessageHandler(
    private val credentials: SignalCredentials,
    private val identityStore: PhotonIdentityKeyStore,
    private val messageDb: SignalMessageDatabase,
    private val contactNames: ContactNames,
    private val groupMetadata: GroupMetadata,
    private val calls: CallHandler,
    private val remoteEdits: RemoteEdits,
    private val contactsSync: ContactsSync,
    private val earlyReads: EarlyReadCache,
    private val notifier: SignalNotifier,
    private val storageMutes: StorageMuteSync,
    private val scope: CoroutineScope,
) {
    fun processSyncMessage(sync: SyncMessage, metadata: EnvelopeMetadata) {
        // What this sync carries that Photon acts on (docs/VERIFY.md looks for this line).
        val kinds = listOfNotNull(
            "sent".takeIf { sync.sent != null },
            "sentEdit".takeIf { sync.sent?.editMessage != null },
            "read[${sync.read.size}]".takeIf { !sync.read.isNullOrEmpty() },
            "viewOnceOpen".takeIf { sync.viewOnceOpen != null },
            "contacts".takeIf { sync.contacts != null },
            "verified".takeIf { sync.verified != null },
            "callEvent".takeIf { sync.callEvent != null },
            "callLogEvent".takeIf { sync.callLogEvent != null },
            "keys".takeIf { sync.keys != null },
            "fetchLatest(${sync.fetchLatest?.type})".takeIf { sync.fetchLatest != null },
        )
        if (kinds.isNotEmpty()) Log.i(TAG, "Sync message kinds: ${kinds.joinToString(",")}")
        // Only the account's own devices may hand over its keys or point at
        // its storage.
        val fromSelf = metadata.sourceServiceId.toString() == credentials.aciString
        sync.keys?.let { keys ->
            if (fromSelf) storageMutes.onKeys(keys.accountEntropyPool) else Log.w(TAG, "Ignoring a Keys sync from another account")
        }
        if (fromSelf && sync.fetchLatest?.type == SyncMessage.FetchLatest.Type.STORAGE_MANIFEST) {
            storageMutes.onStorageChanged()
        }
        sync.verified?.let { verified ->
            try {
                val applied = applySignalVerificationSync(
                    identityStore, verified,
                    metadata.sourceServiceId.toString(), credentials.aciString,
                )
                Log.i(TAG, "Verification sync handled=$applied")
            } catch (e: Exception) {
                Log.w(TAG, "Invalid verification sync", e)
            }
        }
        sync.callEvent?.let { calls.processCallEventSync(it) }
        sync.callLogEvent?.let { calls.processCallLogEvent(it) }
        // Sent messages from our primary device
        sync.sent?.let { processSent(it) }

        // The primary opened a view-once message: it's read here too. Photon
        // never had its media.
        sync.viewOnceOpen?.let { open ->
            val author = serviceIdString(open.senderAci, open.senderAciBinary)
            val timestamp = open.timestamp
            if (author != null && timestamp != null) markReadElsewhere("${author}_$timestamp")
        }

        // Read receipts from primary — mark the matching messages as read AND
        // clear their local notifications so the banner disappears as soon as
        // the user reads elsewhere, not only when they open the chat in Photon.
        val reads = sync.read
        if (!reads.isNullOrEmpty()) {
            for (read in reads) {
                val senderAci = serviceIdString(read.senderAci, read.senderAciBinary) ?: continue
                val timestamp = read.timestamp ?: continue
                markReadElsewhere("${senderAci}_$timestamp")
            }
        }

        // Contacts sync — download the attachment blob and ingest each
        // DeviceContact (name + phone + ACI from the primary's address book).
        val contacts = sync.contacts
        if (contacts != null) {
            val blob = contacts.blob
            if (blob != null) {
                scope.launch { contactsSync.processContactsBlob(blob) }
            } else {
                Log.d(TAG, "Contacts sync had no blob")
            }
        }
    }

    /**
     * The message keyed [prefix] (or an edit of it) was read on another
     * device: mark it read here, clearing the notifications of
     * conversations it emptied, and start its disappearing clock.
     */
    private fun markReadElsewhere(prefix: String) {
        val resolved = messageDb.resolveMessagePrefix(prefix)
        val result = messageDb.markIncomingReadByPrefix(resolved)
        // The read beat its message here; remember it so the message
        // lands already read instead of unread with a notification.
        if (!result.matched) earlyReads.remember(resolved)
        // Only conversations this read actually emptied: newer unread
        // messages keep their notification.
        result.clearedConversations.forEach { conversationJid ->
            notifier.cancelConversation(conversationJid)
        }
    }

    /**
     * A message the user sent from another device (a sent transcript):
     * stored as their own, in the group or DM it went to, row by row as an
     * incoming one is (plannedRows). Reactions, deletes and edits apply to
     * their target instead. In a DM the transcript's timer can move the
     * chat's, by Signal-Android's sent-transcript rule.
     */
    private fun processSent(sent: SyncMessage.Sent) {
        val myAci = credentials.aciString ?: return
        sent.editMessage?.let { edit ->
            processSentEdit(sent, edit, myAci)
            return
        }
        val data = sent.message ?: return
        val timestamp = data.timestamp ?: System.currentTimeMillis()
        // Reactions and deletes-for-everyone the user made on another
        // device carry no body; apply them to the target and stop.
        data.reaction?.let { reaction ->
            remoteEdits.applyReaction(reaction, myAci, timestamp)
            return
        }
        data.delete?.targetSentTimestamp?.let { targetTs ->
            remoteEdits.applyRemoteDelete("${myAci}_$targetTs")
            return
        }
        // Same (author, timestamp) dedupe as incoming data messages: a sent
        // transcript can be redelivered, and the random suffix would
        // otherwise store a duplicate of our own outgoing message.
        val messagePrefix = "${myAci}_${timestamp}"
        val messageId = "${messagePrefix}_${UUID.randomUUID().toString().take(8)}"
        val alreadyStored = messageDb.isMessageStored(messagePrefix)
        val rows = plannedRows(data)
        val expiresIn = (data.expireTimer ?: 0).coerceAtLeast(0)
        // When the user's device started its clock; the library sets it on
        // every transcript of a message with a timer.
        val expireStart = sent.expirationStartTimestamp ?: timestamp

        // Group case: route into the group conversation regardless of
        // destinationServiceId (which is unset for group sends from the
        // primary, and the existing handler used to drop them silently —
        // that was the root cause of groups appearing missing for users
        // who only sent into them rather than received from them). A group's
        // timer comes from its state, never from a message.
        val groupJid = groupJidOf(data)
        if (groupJid != null) {
            if (rows.isNotEmpty() && !alreadyStored) {
                messageDb.insertOwnRows(
                    messageId, rows, groupJid, myAci, timestamp / 1000, replyPrefix(data.quote),
                    expiresIn = expiresIn, expireStartMs = expireStart,
                )
                Log.d(TAG, "Stored sync sent message into group ${groupJid.take(12)}…")
            }
            messageDb.markConversationRead(groupJid)
            notifier.cancelConversation(groupJid)
            return
        }

        val destinationAci = destinationOf(sent) ?: run {
            Log.w(TAG, "Sync sent message has no destination, skipping")
            return
        }
        if ((rows.isNotEmpty() || data.isExpirationUpdate) && !alreadyStored) {
            // Capture destination phone and profile key so profile fetch can find names.
            sent.destinationE164?.let { messageDb.updateContactPhone(destinationAci, it) }
            val syncProfileKey = data.profileKey?.toByteArray()
            if (syncProfileKey != null && syncProfileKey.size == 32) {
                messageDb.updateContactProfileKey(destinationAci, syncProfileKey)
            }

            // The title is resolved when read (SignalNames), from the
            // ACI's contact row: the phone contact sync mapped to it
            // works even when the transcript carries no E.164.
            messageDb.upsertConversation(jid = destinationAci, name = null, isGroup = false)
            if (destinationAci != myAci) contactNames.maybeFetchProfile(destinationAci)
            messageDb.updateDmTimer(destinationAci, lineTimestamp = timestamp / 1000, explicit = data.isExpirationUpdate) { stored ->
                syncedTimerUpdate(stored, expiresIn, data.expireTimerVersion)
            }
            messageDb.insertOwnRows(
                messageId, rows, destinationAci, myAci, timestamp / 1000, replyPrefix(data.quote),
                expiresIn = expiresIn, expireStartMs = expireStart,
            )
        }
        // A sent transcript means the user has seen this thread on the
        // primary. Some primary/device combinations send sync.sent without
        // the corresponding sync.read, so clear any stale local incoming
        // unread state as a conservative fallback.
        messageDb.markConversationRead(destinationAci)
        notifier.cancelConversation(destinationAci)
        Log.d(TAG, "Stored sync sent message to $destinationAci")
    }

    /** The user edited one of their messages on another device. */
    private fun processSentEdit(sent: SyncMessage.Sent, edit: EditMessage, myAci: String) {
        val data = edit.dataMessage ?: return
        val targetTimestamp = edit.targetSentTimestamp ?: return
        val conversationJid = groupJidOf(data) ?: destinationOf(sent) ?: return
        remoteEdits.applyEdit(myAci, targetTimestamp, data, conversationJid, notify = false)
    }

    /** The group a sent message went to, seeding its metadata; null for a DM or an invalid master key. */
    private fun groupJidOf(data: DataMessage): String? {
        val groupV2 = data.groupV2
        val groupMasterKey = groupV2?.masterKey?.toByteArray()?.takeIf { it.size == 32 } ?: return null
        val jid = try { SignalGroupV2Manager.jidFromMasterKey(groupMasterKey) } catch (_: Exception) { null }
        if (jid == null) {
            Log.w(TAG, "Sync sent has an invalid groupV2 master key; falling through")
            return null
        }
        groupMetadata.seedGroupConversation(jid, groupMasterKey, groupV2.revision ?: 0)
        return jid
    }

    /**
     * A DM transcript's destination, keyed as its conversation: PNI
     * destinations are normalized to the ACI so sent-primary transcripts
     * land in the same replyable thread Photon uses for direct sends.
     */
    private fun destinationOf(sent: SyncMessage.Sent): String? {
        val destinationServiceIdRaw = sent.destinationServiceId
        val destinationServiceId = destinationServiceIdRaw?.let { ServiceId.parseOrNull(it) }
            ?: sent.destinationServiceIdBinary?.let {
                try { ServiceId.parseOrNull(it.toByteArray()) }
                catch (_: Exception) { null }
            }
        return destinationServiceId?.let {
            contactNames.normalizeServiceIdForConversation(it, sent.destinationE164, "Sync sent destination")
        }
            ?: contactNames.normalizeServiceIdStringForConversation(
                destinationServiceIdRaw,
                sent.destinationE164,
                "Sync sent destination",
            )
            ?: sent.destinationE164
    }
}
