package app.photon.signal.receiver

import android.util.Log
import app.photon.signal.EarlyReadCache
import app.photon.signal.SignalCredentials
import app.photon.signal.SignalGroupV2Manager
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.applySignalVerificationSync
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.findMessageByPrefix
import app.photon.signal.db.insertMessage
import app.photon.signal.db.markConversationRead
import app.photon.signal.db.markIncomingReadByPrefix
import app.photon.signal.db.updateContactPhone
import app.photon.signal.db.updateContactProfileKey
import app.photon.signal.db.updateConversationLastMessage
import app.photon.signal.db.upsertConversation
import app.photon.signal.replyPrefix
import app.photon.signal.serviceIdString
import app.photon.signal.store.PhotonIdentityKeyStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.internal.push.SyncMessage

private const val TAG = SignalMessageReceiver.TAG

/** SyncMessages from the user's other devices: sent transcripts, reads, contacts, verification and calls. */
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
    private val scope: CoroutineScope,
) {
    fun processSyncMessage(sync: SyncMessage, metadata: EnvelopeMetadata) {
        // What this sync carries that Photon acts on (docs/VERIFY.md looks for this line).
        val kinds = listOfNotNull(
            "sent".takeIf { sync.sent != null },
            "read[${sync.read.size}]".takeIf { !sync.read.isNullOrEmpty() },
            "contacts".takeIf { sync.contacts != null },
            "verified".takeIf { sync.verified != null },
            "callEvent".takeIf { sync.callEvent != null },
            "callLogEvent".takeIf { sync.callLogEvent != null },
        )
        if (kinds.isNotEmpty()) Log.i(TAG, "Sync message kinds: ${kinds.joinToString(",")}")
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
        val sent = sync.sent
        if (sent != null && sent.message != null) {
            val data = sent.message!!
            val timestamp = data.timestamp ?: System.currentTimeMillis()
            val myAci = credentials.aciString ?: return
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
            val alreadyStored = messageDb.findMessageByPrefix(messagePrefix) != null

            // Group case: route into the group conversation regardless of
            // destinationServiceId (which is unset for group sends from the
            // primary, and the existing handler used to drop them silently —
            // that was the root cause of groups appearing missing for users
            // who only sent into them rather than received from them).
            val groupV2 = data.groupV2
            val groupMasterKey = groupV2?.masterKey?.toByteArray()?.takeIf { it.size == 32 }
            if (groupMasterKey != null) {
                val convJid = try { SignalGroupV2Manager.jidFromMasterKey(groupMasterKey) } catch (_: Exception) { null }
                if (convJid != null) {
                    groupMetadata.seedGroupConversation(convJid, groupMasterKey, groupV2.revision ?: 0)

                    val body = data.body
                    val attachment = data.attachments.firstOrNull()
                    if ((body != null || attachment != null) && !alreadyStored) {
                        messageDb.insertMessage(
                            id = messageId,
                            conversationJid = convJid,
                            senderJid = myAci,
                            timestamp = timestamp / 1000,
                            contentType = if (attachment != null) contentTypeForMime(attachment.contentType) else "text",
                            textBody = body,
                            mediaMime = attachment?.contentType,
                            mediaSize = attachment?.size?.toLong(),
                            replyToId = replyPrefix(data.quote),
                            isFromMe = true,
                            status = "sent",
                            rawProto = attachment?.encode(),
                        )
                        messageDb.updateConversationLastMessage(convJid, messageId, timestamp / 1000)
                        Log.d(TAG, "Stored sync sent message into group ${convJid.take(12)}…")
                    }
                    messageDb.markConversationRead(convJid)
                    notifier.cancelConversation(convJid)
                    return
                }
                Log.w(TAG, "Sync sent has an invalid groupV2 master key; falling through")
            }

            // DM case: needs a destination on the sync transcript. Normalize
            // PNI destinations to ACI so sent-primary transcripts land in the
            // same replyable thread Photon uses for direct sends.
            val destinationServiceIdRaw = sent.destinationServiceId
            val destinationServiceId = destinationServiceIdRaw?.let { ServiceId.parseOrNull(it) }
                ?: sent.destinationServiceIdBinary?.let {
                    try { ServiceId.parseOrNull(it.toByteArray()) }
                    catch (_: Exception) { null }
                }
            val destinationAci = destinationServiceId?.let {
                contactNames.normalizeServiceIdForConversation(it, sent.destinationE164, "Sync sent destination")
            }
                ?: contactNames.normalizeServiceIdStringForConversation(
                    destinationServiceIdRaw,
                    sent.destinationE164,
                    "Sync sent destination",
                )
                ?: sent.destinationE164
                ?: run {
                    Log.w(TAG, "Sync sent message has no destination, skipping")
                    return
                }

            val body = data.body
            val attachment = data.attachments.firstOrNull()
            if ((body != null || attachment != null) && !alreadyStored) {
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
                messageDb.insertMessage(
                    id = messageId,
                    conversationJid = destinationAci,
                    senderJid = myAci,
                    timestamp = timestamp / 1000,
                    contentType = if (attachment != null) contentTypeForMime(attachment.contentType) else "text",
                    textBody = body,
                    mediaMime = attachment?.contentType,
                    mediaSize = attachment?.size?.toLong(),
                    replyToId = replyPrefix(data.quote),
                    isFromMe = true,
                    status = "sent",
                    rawProto = attachment?.encode(),
                )
                messageDb.updateConversationLastMessage(destinationAci, messageId, timestamp / 1000)
            }
            // A sent transcript means the user has seen this thread on the
            // primary. Some primary/device combinations send sync.sent without
            // the corresponding sync.read, so clear any stale local incoming
            // unread state as a conservative fallback.
            messageDb.markConversationRead(destinationAci)
            notifier.cancelConversation(destinationAci)
            Log.d(TAG, "Stored sync sent message to $destinationAci")
        }

        // Read receipts from primary — mark the matching messages as read AND
        // clear their local notifications so the banner disappears as soon as
        // the user reads elsewhere, not only when they open the chat in Photon.
        val reads = sync.read
        if (!reads.isNullOrEmpty()) {
            for (read in reads) {
                val senderAci = serviceIdString(read.senderAci, read.senderAciBinary) ?: continue
                val timestamp = read.timestamp ?: continue
                val prefix = "${senderAci}_$timestamp"
                val result = messageDb.markIncomingReadByPrefix(prefix)
                // The read beat its message here; remember it so the message
                // lands already read instead of unread with a notification.
                if (!result.matched) earlyReads.remember(prefix)
                // Only conversations this read actually emptied: newer unread
                // messages keep their notification.
                result.clearedConversations.forEach { conversationJid ->
                    notifier.cancelConversation(conversationJid)
                }
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
}
