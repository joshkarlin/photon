package app.photon.signal.receiver

import android.util.Base64
import android.util.Log
import app.photon.signal.EarlyReadCache
import app.photon.signal.MessageKeys
import app.photon.signal.SignalGroupV2Manager
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.insertIncomingRows
import app.photon.signal.db.isMessageStored
import app.photon.signal.db.updateContactPhone
import app.photon.signal.db.updateContactProfileKey
import app.photon.signal.db.updateDmTimer
import app.photon.signal.db.upsertConversation
import app.photon.signal.db.upsertParticipant
import app.photon.signal.incomingTimerUpdate
import app.photon.signal.notificationContent
import app.photon.signal.plannedRows
import app.photon.signal.replyPrefix
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.EditMessage

private const val TAG = SignalMessageReceiver.TAG

/** Whether a DataMessage is an explicit disappearing-messages timer change. */
internal val DataMessage.isExpirationUpdate: Boolean
    get() = (flags ?: 0) and DataMessage.Flags.EXPIRATION_TIMER_UPDATE.value != 0

/**
 * Incoming DataMessages and edits: text, media, contacts, polls, view-once
 * media, timer changes, reactions, deletes and group-call announcements.
 */
internal class DataMessageHandler(
    private val messageDb: SignalMessageDatabase,
    private val contactNames: ContactNames,
    private val groupMetadata: GroupMetadata,
    private val calls: CallHandler,
    private val remoteEdits: RemoteEdits,
    private val attachments: Attachments,
    // Read syncs that arrived before their message (see EarlyReadCache).
    private val earlyReads: EarlyReadCache,
    private val messageNotifications: MessageNotifications,
    private val scope: CoroutineScope,
) {
    /** Who sent an envelope, keyed as their conversation is (their ACI). */
    private fun senderOf(metadata: EnvelopeMetadata): String = contactNames.normalizeServiceIdForConversation(
        serviceId = metadata.sourceServiceId,
        e164 = metadata.sourceE164,
        context = "Incoming data source",
    )

    /**
     * The conversation a message from [senderAci] belongs to. Two sources
     * of "this is a group": (a) libsignal extracted `metadata.groupId`
     * during decryption (the 16-byte stable identifier), or (b) the
     * DataMessage carries `groupV2.masterKey`, from which we can derive the
     * same identifier ourselves. The masterKey path is preferred because it
     * gives us the material needed to fetch + decrypt the group's title and
     * members.
     *
     * Seeds the group's metadata whatever the message carries: many in-group
     * messages have no body (sender-key distribution prelude, group state
     * changes, typing indicators with no payload, etc.) but DO carry
     * `groupV2.masterKey`, the only material needed to fetch the group's
     * name, members and timer. Bailing out without storing it leaves the
     * group permanently unnamed and unable to receive outgoing sends.
     */
    private fun conversationOf(data: DataMessage, metadata: EnvelopeMetadata, senderAci: String): Pair<String, Boolean> {
        val groupV2 = data.groupV2
        val groupMasterKey = groupV2?.masterKey?.toByteArray()?.takeIf { it.size == 32 }
        val derivedGroupId = groupMasterKey?.let { mk ->
            try { SignalGroupV2Manager.groupIdFromMasterKey(mk) } catch (_: Exception) { null }
        }
        val groupIdBytes = derivedGroupId ?: metadata.groupId
        if (groupIdBytes == null || groupIdBytes.isEmpty()) return senderAci to false
        val conversationJid = Base64.encodeToString(groupIdBytes, Base64.NO_WRAP or Base64.URL_SAFE)
        if (groupMasterKey != null) {
            groupMetadata.seedGroupConversation(conversationJid, groupMasterKey, groupV2.revision ?: 0)
        }
        return conversationJid to true
    }

    fun processDataMessage(data: DataMessage, metadata: EnvelopeMetadata) {
        val senderE164 = metadata.sourceE164
        val senderAci = senderOf(metadata)
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

        val (conversationJid, isGroup) = conversationOf(data, metadata, senderAci)

        // Stable identity of a Signal message is (author, sentTimestamp); the
        // "_{rand}" suffix only makes the local PK unique. The same message can
        // be delivered more than once — the sender resends after our retry
        // receipt / sender-key redistribution, and the server can redeliver an
        // un-acked envelope — so dedupe on the stable prefix before inserting.
        // Without this each redelivery minted a fresh random id and stored a
        // duplicate row (one group message arrived 36 times).
        val messagePrefix = "${senderAci}_${timestamp}"
        val messageId = "${messagePrefix}_${UUID.randomUUID().toString().take(8)}"

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
            if (isGroup) calls.recordGroupCall(conversationJid, senderAci, update.eraId, timestamp)
            return
        }

        // Everything else that shows: one row per attachment, contact, poll
        // or body (plannedRows), or an explicit timer change, which shows as
        // its line.
        val rows = plannedRows(data)
        val timerChange = data.isExpirationUpdate
        if (rows.isEmpty() && !timerChange) {
            // No displayable content (could be key update, profile key, etc.)
            return
        }
        // A redelivery changes nothing, not even the timer: an old message
        // without a timer version would otherwise set an old timer again.
        if (rows.isNotEmpty() && messageDb.isMessageStored(messagePrefix)) {
            Log.d(TAG, "Skipping duplicate incoming message $messagePrefix")
            return
        }

        if (isGroup) {
            // Master key + conversation row already seeded above (before
            // the body check). Here we only record the sender as a member.
            messageDb.upsertParticipant(conversationJid, senderAci)
        } else {
            // Names are resolved when read (SignalNames), not stored.
            messageDb.upsertConversation(jid = conversationJid, name = null, isGroup = false)
        }

        // Each message carries its chat's disappearing-messages timer. In a
        // 1:1 chat it can change the chat's timer (by Signal-Android's
        // version rule); a group's timer comes only from the group's state,
        // as Signal-Android ignores the timer on group messages. Either way
        // the message itself disappears after its own timer, once read.
        val expiresIn = (data.expireTimer ?: 0).coerceAtLeast(0)
        if (!isGroup) {
            messageDb.updateDmTimer(conversationJid, lineTimestamp = timestamp / 1000, explicit = timerChange) { stored ->
                incomingTimerUpdate(stored, expiresIn, data.expireTimerVersion)
            }
        }
        if (rows.isEmpty()) return

        // A read sync from another device may have beaten this message here
        // (e.g. it was resent after a decrypt failure). Then it's already
        // read: store it without counting it unread or notifying.
        val readElsewhere = earlyReads.consume(messagePrefix)

        // Redeliveries are dropped inside the insert too (same author+timestamp
        // prefix), skipping every side effect below so a resent message
        // doesn't appear N times, inflate the unread count, or re-notify.
        val inserted = messageDb.insertIncomingRows(
            messageId = messageId,
            rows = rows,
            conversationJid = conversationJid,
            senderJid = senderAci,
            timestamp = timestamp / 1000,
            replyToId = replyPrefix(data.quote),
            expiresIn = expiresIn,
            alreadyRead = readElsewhere,
        )
        if (!inserted) {
            Log.d(TAG, "Skipping duplicate incoming message $messagePrefix")
            return
        }

        // Voice notes auto-download (small, and the UI autoplays them on
        // open) — mirrors the WhatsApp bridge's auto-download policy.
        rows.forEachIndexed { index, row ->
            if (row.attachment != null && row.contentType == "audio") {
                val partId = MessageKeys.partId(messageId, index)
                scope.launch { attachments.downloadAttachment(partId) }
            }
        }

        if (readElsewhere) {
            Log.i(TAG, "Stored message $messagePrefix as read (read sync arrived first)")
            return
        }

        val (previewText, previewType) = notificationContent(rows, data.body)
        messageNotifications.showMessage(conversationJid, isGroup, senderAci, previewText, previewType)
    }

    /**
     * Someone edited a message they sent. The edit is a message of its own
     * naming the original by its sent timestamp; its text replaces the
     * original's in place (RemoteEdits.applyEdit). It changes no timer.
     */
    fun processEditMessage(edit: EditMessage, metadata: EnvelopeMetadata) {
        val data = edit.dataMessage ?: return
        val targetTimestamp = edit.targetSentTimestamp ?: return
        val senderAci = senderOf(metadata)
        val (conversationJid, _) = conversationOf(data, metadata, senderAci)
        remoteEdits.applyEdit(senderAci, targetTimestamp, data, conversationJid, notify = true)
    }
}
