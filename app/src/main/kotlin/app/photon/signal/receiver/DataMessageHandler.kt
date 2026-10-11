package app.photon.signal.receiver

import android.util.Base64
import android.util.Log
import app.photon.signal.EarlyReadCache
import app.photon.signal.SignalGroupV2Manager
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.insertIncomingMessage
import app.photon.signal.db.updateContactPhone
import app.photon.signal.db.updateContactProfileKey
import app.photon.signal.db.upsertConversation
import app.photon.signal.db.upsertParticipant
import app.photon.signal.replyPrefix
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.internal.push.DataMessage

private const val TAG = SignalMessageReceiver.TAG

/** Incoming DataMessages: text, media, reactions, deletes and group-call announcements. */
internal class DataMessageHandler(
    private val messageDb: SignalMessageDatabase,
    private val contactNames: ContactNames,
    private val groupMetadata: GroupMetadata,
    private val calls: CallHandler,
    private val remoteEdits: RemoteEdits,
    private val attachments: Attachments,
    // Read syncs that arrived before their message (see EarlyReadCache).
    private val earlyReads: EarlyReadCache,
    private val notifier: SignalNotifier,
    private val scope: CoroutineScope,
) {
    fun processDataMessage(data: DataMessage, metadata: EnvelopeMetadata) {
        val senderE164 = metadata.sourceE164
        val senderAci = contactNames.normalizeServiceIdForConversation(
            serviceId = metadata.sourceServiceId,
            e164 = senderE164,
            context = "Incoming data source",
        )
        val timestamp = data.timestamp ?: System.currentTimeMillis()

        // Phone number is the cheapest fallback name; profile fetch (below) may upgrade it.
        if (senderE164 != null) {
            messageDb.updateContactPhone(senderAci, senderE164)
        }

        // DataMessages typically include the sender's profile key — capture it so we can
        // later fetch their Signal profile (display name, avatar) via ProfileService.
        val profileKeyBytes = data.profileKey?.toByteArray()
        if (profileKeyBytes != null && profileKeyBytes.size == 32) {
            messageDb.updateContactProfileKey(senderAci, profileKeyBytes)
        }
        // Kick off an async profile fetch whenever we haven't resolved a display name yet.
        contactNames.maybeFetchProfile(senderAci)

        // Determine conversation JID. Two sources of "this is a group":
        // (a) libsignal extracted `metadata.groupId` during decryption (the
        //     16-byte stable identifier), or
        // (b) the DataMessage carries `groupV2.masterKey`, from which we
        //     can derive the same identifier ourselves.
        // We prefer the masterKey path because it gives us the material
        // needed to fetch + decrypt the group's title and members.
        val groupV2 = data.groupV2
        val groupMasterKey = groupV2?.masterKey?.toByteArray()?.takeIf { it.size == 32 }
        val groupRevision = groupV2?.revision ?: 0

        val metaGroupId = metadata.groupId
        val derivedGroupId = groupMasterKey?.let { mk ->
            try { SignalGroupV2Manager.groupIdFromMasterKey(mk) } catch (_: Exception) { null }
        }

        val groupIdBytes = derivedGroupId ?: metaGroupId
        val isGroup = groupIdBytes != null && groupIdBytes.isNotEmpty()
        val conversationJid = if (isGroup) {
            Base64.encodeToString(groupIdBytes, Base64.NO_WRAP or Base64.URL_SAFE)
        } else {
            senderAci
        }

        // Stable identity of a Signal message is (author, sentTimestamp); the
        // "_{rand}" suffix only makes the local PK unique. The same message can
        // be delivered more than once — the sender resends after our retry
        // receipt / sender-key redistribution, and the server can redeliver an
        // un-acked envelope — so dedupe on the stable prefix before inserting.
        // Without this each redelivery minted a fresh random id and stored a
        // duplicate row (one group message arrived 36 times).
        val messagePrefix = "${senderAci}_${timestamp}"
        val messageId = "${messagePrefix}_${UUID.randomUUID().toString().take(8)}"

        // Seed group metadata BEFORE the body-vs-sticker early-return below.
        // Many in-group messages have no body or sticker (sender-key
        // distribution prelude, group state changes, typing indicators with
        // no payload, etc.) — but they often DO carry `groupV2.masterKey`,
        // which is the only material we need to fetch the group's name and
        // member list. Bailing out without storing it leaves the group
        // permanently unnamed and unable to receive outgoing sends.
        if (isGroup && groupMasterKey != null) {
            groupMetadata.seedGroupConversation(conversationJid, groupMasterKey, groupRevision)
        }

        // Handle reactions (and reaction removals)
        data.reaction?.let { reaction ->
            remoteEdits.applyReaction(reaction, senderAci, timestamp)
            return
        }

        // Handle deletes (remote delete-for-everyone). Key by the
        // "{author}_{timestampMs}" prefix — the stored id has a "_{rand}" suffix
        // the delete proto doesn't carry, so an exact-id match never matched.
        if (data.delete != null) {
            val targetTs = data.delete!!.targetSentTimestamp ?: return
            remoteEdits.applyRemoteDelete("${senderAci}_$targetTs")
            return
        }

        // A group call started (or someone joined one). Photon can't join;
        // record it like a message so the chat shows it. Group-call update
        // messages have no body, so this must run before the empty check.
        data.groupCallUpdate?.let { update ->
            if (isGroup) {
                val name = contactNames.displayNameFor(senderAci, senderE164)
                calls.recordGroupCall(conversationJid, senderAci, name, update.eraId, timestamp)
            }
            return
        }

        // Regular message
        val body = data.body
        val attachment = data.attachments.firstOrNull()
        if (body == null && data.sticker == null && attachment == null) {
            // No displayable content (could be key update, profile key, etc.)
            return
        }

        // Media beats text: a photo with a caption is an "image" row whose
        // text_body carries the caption, mirroring the WhatsApp model.
        val contentType = when {
            attachment != null -> contentTypeForMime(attachment.contentType)
            body != null -> "text"
            else -> "sticker"
        }

        val senderName = contactNames.displayNameFor(senderAci, senderE164)

        if (isGroup) {
            // Master key + conversation row already seeded above (before
            // the body check). Here we only do the per-message work:
            // record the sender as a known participant so per-message
            // sender labels render in group transcripts.
            if (!senderName.isNullOrBlank()) {
                messageDb.upsertParticipant(conversationJid, senderAci, senderName, "member")
            }
        } else {
            messageDb.upsertConversation(jid = conversationJid, name = senderName, isGroup = false)
        }

        // A read sync from another device may have beaten this message here
        // (e.g. it was resent after a decrypt failure). Then it's already
        // read: store it without counting it unread or notifying.
        val readElsewhere = earlyReads.consume(messagePrefix)

        // Redeliveries are dropped inside the insert (same author+timestamp
        // prefix), skipping every side effect below so a resent message
        // doesn't appear N times, inflate the unread count, or re-notify.
        val inserted = messageDb.insertIncomingMessage(
            id = messageId,
            conversationJid = conversationJid,
            senderJid = senderAci,
            timestamp = timestamp / 1000,
            contentType = contentType,
            textBody = body,
            mediaMime = attachment?.contentType,
            mediaSize = attachment?.size?.toLong(),
            replyToId = replyPrefix(data.quote),
            // Serialized pointer so the blob can be fetched on demand later
            // (tap-to-view); images/videos aren't auto-downloaded.
            rawProto = attachment?.encode(),
            dedupePrefix = messagePrefix,
            alreadyRead = readElsewhere,
        )
        if (!inserted) {
            Log.d(TAG, "Skipping duplicate incoming message $messagePrefix")
            return
        }

        // Voice notes auto-download (small, and the UI autoplays them on
        // open) — mirrors the WhatsApp bridge's auto-download policy.
        if (attachment != null && contentType == "audio") {
            scope.launch { attachments.downloadAttachment(messageId) }
        }

        if (readElsewhere) {
            Log.i(TAG, "Stored message $messagePrefix as read (read sync arrived first)")
            return
        }

        // Show notification — for groups, title is the group name (with the
        // sender's name woven into the body); for DMs it's the sender.
        val notifTitle = if (isGroup) {
            groupMetadata.groupTitle(conversationJid)
        } else {
            senderName ?: senderAci.take(8)
        }
        val preview = body ?: "[$contentType]"
        val notifBody = if (isGroup && !senderName.isNullOrBlank()) {
            "$senderName: $preview"
        } else preview
        notifier.showMessage(notifTitle, notifBody, conversationJid)
    }
}
