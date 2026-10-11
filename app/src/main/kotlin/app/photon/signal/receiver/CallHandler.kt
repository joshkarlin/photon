package app.photon.signal.receiver

import android.util.Log
import app.photon.signal.CallOutcome
import app.photon.signal.CallSignal
import app.photon.signal.CallState
import app.photon.signal.SignalCalls
import app.photon.signal.SignalCredentials
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.deleteCallRow
import app.photon.signal.db.findCallRowId
import app.photon.signal.db.getConversation
import app.photon.signal.db.getMessage
import app.photon.signal.db.insertIncomingMessage
import app.photon.signal.db.markCallRowsRead
import app.photon.signal.db.updateContactPhone
import app.photon.signal.db.upsertCallRow
import app.photon.signal.db.upsertConversation
import app.photon.signal.db.upsertParticipant
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.api.messages.EnvelopeResponse
import org.whispersystems.signalservice.internal.push.CallMessage
import org.whispersystems.signalservice.internal.push.SyncMessage

private const val TAG = SignalMessageReceiver.TAG

/** Signal calls as call-log rows and ringing / missed notifications (see [SignalCalls]). */
internal class CallHandler(
    private val credentials: SignalCredentials,
    private val messageDb: SignalMessageDatabase,
    private val contactNames: ContactNames,
    private val groupMetadata: GroupMetadata,
    private val notifier: SignalNotifier,
) {
    /**
     * 1:1 call signalling. Photon can't take part in the call (no RingRTC),
     * so it only reports it: an offer rings via a notification and records
     * an "Incoming … call" row; hangups/busy turn that into missed, answered
     * elsewhere or declined. Answer, ICE and opaque (group ring) messages
     * are WebRTC plumbing and are ignored.
     */
    fun processCallMessage(
        call: CallMessage,
        metadata: EnvelopeMetadata,
        envelopeResponse: EnvelopeResponse,
    ) {
        val localDeviceId = credentials.deviceId
        val destination = call.destinationDeviceId
        if (destination != null && destination != localDeviceId) {
            Log.d(TAG, "Ignoring call message for device $destination")
            return
        }
        // Call signalling between the user's own devices isn't a call to them.
        if (metadata.sourceServiceId.toString() == credentials.aciString) return
        val peerAci = contactNames.normalizeServiceIdForConversation(
            serviceId = metadata.sourceServiceId,
            e164 = metadata.sourceE164,
            context = "Incoming call source",
        )
        val envelope = envelopeResponse.envelope
        val timestampMs = envelope.clientTimestamp ?: envelope.serverTimestamp ?: System.currentTimeMillis()

        val offer = call.offer
        val hangup = call.hangup
        val busy = call.busy
        when {
            offer != null -> {
                val callId = offer.id ?: return
                val stale = SignalCalls.isStaleOffer(
                    serverReceivedMs = envelope.serverTimestamp,
                    serverDeliveredMs = envelopeResponse.serverDeliveredTimestamp,
                    nowMs = System.currentTimeMillis(),
                )
                if (stale) Log.i(TAG, "Call offer $callId is stale; recording as missed without ringing")
                applyCallSignal(
                    peerAci, metadata.sourceE164, callId,
                    if (stale) CallSignal.STALE_OFFER else CallSignal.OFFER,
                    offerIsVideo = offer.type == CallMessage.Offer.Type.OFFER_VIDEO_CALL,
                    timestampMs = timestampMs,
                )
            }
            hangup != null -> {
                val callId = hangup.id ?: return
                val signal = SignalCalls.signalForHangup(hangup.type, hangup.deviceId, localDeviceId) ?: return
                applyCallSignal(peerAci, metadata.sourceE164, callId, signal, null, timestampMs)
            }
            busy != null -> {
                val callId = busy.id ?: return
                applyCallSignal(peerAci, metadata.sourceE164, callId, CallSignal.ENDED_UNANSWERED, null, timestampMs)
            }
        }
    }

    private fun applyCallSignal(
        peerAci: String,
        peerE164: String?,
        callId: Long,
        signal: CallSignal,
        offerIsVideo: Boolean?,
        timestampMs: Long,
    ) {
        val id = SignalCalls.rowId(peerAci, callId)
        val existing = messageDb.getMessage(id)
        if (existing == null && signal != CallSignal.OFFER && signal != CallSignal.STALE_OFFER) {
            Log.d(TAG, "Call $callId: $signal for a call we never saw ringing; ignoring")
            return
        }
        if (existing == null) {
            // First sight of this caller may be the call itself.
            peerE164?.let { messageDb.updateContactPhone(peerAci, it) }
            messageDb.upsertConversation(jid = peerAci, name = contactNames.displayNameFor(peerAci, peerE164), isGroup = false)
            contactNames.maybeFetchProfile(peerAci)
        }
        updateCallRow(id, peerAci, existing?.textBody, signal, offerIsVideo, timestampMs / 1000)
    }

    private fun updateCallRow(
        id: String,
        conversationJid: String,
        currentText: String?,
        signal: CallSignal,
        offerIsVideo: Boolean?,
        timestampSeconds: Long,
    ) {
        val current = SignalCalls.parse(currentText)
        val outcome = SignalCalls.next(current?.outcome, signal) ?: return
        val state = CallState(outcome, current?.isVideo ?: offerIsVideo ?: false)
        val change = messageDb.upsertCallRow(
            id = id,
            conversationJid = conversationJid,
            senderJid = conversationJid,
            timestamp = timestampSeconds,
            text = SignalCalls.text(state),
            unread = SignalCalls.isUnread(outcome),
        )
        Log.i(TAG, "Call ${id.takeLast(12)}: ${current?.outcome ?: "new"} -> $outcome ($signal)")

        when (outcome) {
            CallOutcome.RINGING ->
                notifier.showIncomingCall(callerName(conversationJid), conversationJid, state.isVideo)
            CallOutcome.MISSED ->
                notifier.showMissedCall(callerName(conversationJid), conversationJid, state.isVideo)
            CallOutcome.ANSWERED, CallOutcome.DECLINED -> {
                notifier.cancelIncomingCall(conversationJid)
                // A call shown as missed turned out to be answered elsewhere.
                if (change.wasUnread && change.unreadCount == 0) {
                    notifier.cancelConversation(conversationJid)
                }
            }
        }
    }

    private fun callerName(conversationJid: String): String =
        messageDb.getConversation(conversationJid)?.name?.takeIf { it.isNotBlank() }
            ?: contactNames.displayNameFor(conversationJid, null)
            ?: conversationJid.take(8)

    /**
     * Another device's view of a call: answered, declined or deleted there.
     * Group/ad-hoc call events are keyed by era, not callId, so they can't be
     * matched to Photon's group-call rows and are skipped.
     */
    fun processCallEventSync(event: SyncMessage.CallEvent) {
        val callId = event.callId ?: return
        if (event.type == SyncMessage.CallEvent.Type.GROUP_CALL ||
            event.type == SyncMessage.CallEvent.Type.AD_HOC_CALL
        ) {
            Log.d(TAG, "Ignoring ${event.type} call event sync")
            return
        }
        val conversationJid = event.conversationId?.toByteArray()?.let { SignalCalls.conversationJidFor(it) }
        val id = conversationJid?.let { SignalCalls.rowId(it, callId) }
            ?.takeIf { messageDb.getMessage(it) != null }
            ?: messageDb.findCallRowId(callId)
            ?: run {
                Log.d(TAG, "Call event ${event.event} for unknown call $callId")
                return
            }
        val row = messageDb.getMessage(id) ?: return
        when (event.event) {
            SyncMessage.CallEvent.Event.DELETE -> {
                val change = messageDb.deleteCallRow(id) ?: return
                notifier.cancelIncomingCall(change.conversationJid)
                if (change.wasUnread && change.unreadCount == 0) {
                    notifier.cancelConversation(change.conversationJid)
                }
            }
            SyncMessage.CallEvent.Event.ACCEPTED ->
                updateCallRow(id, row.conversationJid, row.textBody, CallSignal.ANSWERED_ELSEWHERE, null, row.timestamp)
            SyncMessage.CallEvent.Event.NOT_ACCEPTED ->
                updateCallRow(id, row.conversationJid, row.textBody, CallSignal.DECLINED_ELSEWHERE, null, row.timestamp)
            else -> Log.d(TAG, "Ignoring call event ${event.event}")
        }
    }

    /** Call log read on another device: clear the matching missed calls here. */
    fun processCallLogEvent(event: SyncMessage.CallLogEvent) {
        val conversationJid = when (event.type) {
            SyncMessage.CallLogEvent.Type.MARKED_AS_READ -> null
            SyncMessage.CallLogEvent.Type.MARKED_AS_READ_IN_CONVERSATION ->
                event.conversationId?.toByteArray()?.let { SignalCalls.conversationJidFor(it) } ?: return
            else -> {
                Log.d(TAG, "Ignoring call log event ${event.type}")
                return
            }
        }
        val upTo = (event.timestamp ?: System.currentTimeMillis()) / 1000
        messageDb.markCallRowsRead(conversationJid, upTo).forEach { jid ->
            notifier.cancelConversation(jid)
        }
    }

    /**
     * Someone started (or joined) a group call. One row per call era, however
     * many members announce it; notifies like a message, never rings.
     */
    fun recordGroupCall(
        groupJid: String,
        senderAci: String,
        senderName: String?,
        eraId: String?,
        timestampMs: Long,
    ) {
        if (!senderName.isNullOrBlank()) {
            messageDb.upsertParticipant(groupJid, senderAci, senderName, "member")
        }
        val id = SignalCalls.groupRowId(groupJid, eraId?.takeIf { it.isNotBlank() } ?: timestampMs.toString())
        val inserted = messageDb.insertIncomingMessage(
            id = id,
            conversationJid = groupJid,
            senderJid = senderAci,
            timestamp = timestampMs / 1000,
            contentType = SignalMessageDatabase.CONTENT_CALL,
            textBody = SignalCalls.GROUP_CALL_TEXT,
            dedupePrefix = null,
        )
        if (!inserted) return
        val body = if (!senderName.isNullOrBlank()) "$senderName started a group call" else SignalCalls.GROUP_CALL_TEXT
        notifier.showMessage(groupMetadata.groupTitle(groupJid), body, groupJid)
    }
}
