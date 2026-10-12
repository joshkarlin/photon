package app.photon.signal.receiver

import android.util.Log
import app.photon.signal.CallOutcome
import app.photon.signal.CallSignal
import app.photon.signal.CallState
import app.photon.signal.SignalCalls
import app.photon.signal.SignalCredentials
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.SignalNames
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.deleteCallRow
import app.photon.signal.db.findCallRowId
import app.photon.signal.db.getCallRowsWithText
import app.photon.signal.db.getMessage
import app.photon.signal.db.insertIncomingMessage
import app.photon.signal.db.isConversationMuted
import app.photon.signal.db.markCallRowsRead
import app.photon.signal.db.updateContactPhone
import app.photon.signal.db.upsertCallRow
import app.photon.signal.db.upsertConversation
import app.photon.signal.db.upsertParticipant
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.api.messages.EnvelopeResponse
import org.whispersystems.signalservice.internal.push.CallMessage
import org.whispersystems.signalservice.internal.push.SyncMessage
import java.util.concurrent.ConcurrentHashMap

private const val TAG = SignalMessageReceiver.TAG

/** Signal calls as call-log rows and ringing / missed notifications (see [SignalCalls]). */
internal class CallHandler(
    private val credentials: SignalCredentials,
    private val messageDb: SignalMessageDatabase,
    private val contactNames: ContactNames,
    private val names: SignalNames,
    private val notifier: SignalNotifier,
    // Runs a task after a delay (a coroutine on the receiver's scope).
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val pendingExpiries = ConcurrentHashMap.newKeySet<String>()

    private companion object {
        // Call rows are read, then rewritten from the read state: the read
        // loop and ring-expiry timers mustn't interleave on one row. Process
        // wide, because a stopped receiver's timers can still fire after a
        // new receiver has started.
        val callLock = Any()
    }

    /**
     * 1:1 call signalling. Photon can't take part in the call (no RingRTC),
     * so it only reports it: an offer rings via a notification and records
     * an "Incoming … call" row; hangups/busy turn that into missed, answered
     * elsewhere or declined. A muted chat still rings and shows missed
     * calls, as on WhatsApp. Answer, ICE and opaque (group ring) messages
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
                val now = clock()
                val stale = SignalCalls.isStaleOffer(
                    serverReceivedMs = envelope.serverTimestamp,
                    serverDeliveredMs = envelopeResponse.serverDeliveredTimestamp,
                    nowMs = now,
                )
                if (stale) Log.i(TAG, "Call offer $callId is stale; recording as missed without ringing")
                val outcome = applyCallSignal(
                    peerAci, metadata.sourceE164, callId,
                    if (stale) CallSignal.STALE_OFFER else CallSignal.OFFER,
                    offerIsVideo = offer.type == CallMessage.Offer.Type.OFFER_VIDEO_CALL,
                    timestampMs = timestampMs,
                )
                if (outcome == CallOutcome.RINGING) {
                    // Timed from the server's receipt of the offer, not the
                    // caller's clock.
                    val ringingSince = envelope.serverTimestamp?.takeIf { it > 0 } ?: now
                    scheduleRingExpiry(SignalCalls.rowId(peerAci, callId), ringingSince + SignalCalls.RING_TIMEOUT_MS)
                }
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

    /** Apply [signal] to the call's row. Returns the row's new outcome, or null if nothing changed. */
    private fun applyCallSignal(
        peerAci: String,
        peerE164: String?,
        callId: Long,
        signal: CallSignal,
        offerIsVideo: Boolean?,
        timestampMs: Long,
    ): CallOutcome? = synchronized(callLock) {
        val id = SignalCalls.rowId(peerAci, callId)
        val existing = messageDb.getMessage(id)
        if (existing == null && signal != CallSignal.OFFER && signal != CallSignal.STALE_OFFER) {
            Log.d(TAG, "Call $callId: $signal for a call we never saw ringing; ignoring")
            return null
        }
        if (existing == null) {
            // First sight of this caller may be the call itself.
            peerE164?.let { messageDb.updateContactPhone(peerAci, it) }
            messageDb.upsertConversation(jid = peerAci, name = null, isGroup = false)
            contactNames.maybeFetchProfile(peerAci)
        }
        updateCallRow(id, peerAci, existing?.textBody, signal, offerIsVideo, timestampMs / 1000)
    }

    /**
     * Rows still ringing that no timer is watching: from before a restart,
     * or renamed by a PNI merge. Those whose ring timeout has passed expire
     * now (quietly once it's long past), the rest get a timer.
     */
    fun expireStaleRinging() {
        val now = clock()
        for (row in messageDb.getCallRowsWithText(SignalCalls.RINGING_TEXTS)) {
            val dueAtMs = row.timestamp * 1000 + SignalCalls.RING_TIMEOUT_MS
            if (now - dueAtMs > SignalCalls.QUIET_EXPIRY_AFTER_MS) {
                expireRinging(row.id, quiet = true)
            } else {
                scheduleRingExpiry(row.id, dueAtMs)
            }
        }
    }

    /**
     * Nothing guarantees a hangup or call-event sync reaches this device, so
     * a ringing row is given up on at [dueAtMs]: by then the call is over.
     */
    private fun scheduleRingExpiry(id: String, dueAtMs: Long) {
        if (!pendingExpiries.add(id)) return
        val task: () -> Unit = {
            pendingExpiries.remove(id)
            try {
                expireRinging(id)
            } catch (e: Exception) {
                Log.w(TAG, "Ring expiry failed for call ${id.takeLast(12)}: ${e.message}")
            }
        }
        val delayMs = dueAtMs - clock()
        if (delayMs <= 0) task() else schedule(delayMs, task)
    }

    private fun expireRinging(id: String, quiet: Boolean = false) {
        synchronized(callLock) {
            val row = messageDb.getMessage(id) ?: return
            updateCallRow(id, row.conversationJid, row.textBody, CallSignal.RING_EXPIRED, null, row.timestamp, quiet)
        }
    }

    /** [quiet]: record the outcome without counting it unread or notifying. */
    private fun updateCallRow(
        id: String,
        conversationJid: String,
        currentText: String?,
        signal: CallSignal,
        offerIsVideo: Boolean?,
        timestampSeconds: Long,
        quiet: Boolean = false,
    ): CallOutcome? {
        val current = SignalCalls.parse(currentText)
        val outcome = SignalCalls.next(current?.outcome, signal) ?: return null
        val state = CallState(outcome, current?.isVideo ?: offerIsVideo ?: false)
        val change = messageDb.upsertCallRow(
            id = id,
            conversationJid = conversationJid,
            senderJid = conversationJid,
            timestamp = timestampSeconds,
            text = SignalCalls.text(state),
            unread = SignalCalls.isUnread(outcome) && !quiet,
        )
        Log.i(TAG, "Call ${id.takeLast(12)}: ${current?.outcome ?: "new"} -> $outcome ($signal${if (quiet) ", quiet" else ""})")
        if (quiet) return outcome

        when (outcome) {
            CallOutcome.RINGING ->
                notifier.showIncomingCall(names.person(conversationJid), conversationJid, state.isVideo)
            CallOutcome.MISSED ->
                notifier.showMissedCall(names.person(conversationJid), conversationJid, state.isVideo)
            CallOutcome.ANSWERED, CallOutcome.DECLINED -> {
                notifier.cancelIncomingCall(conversationJid)
                // A call shown as missed turned out to be answered elsewhere.
                if (change.wasUnread && change.unreadCount == 0) {
                    notifier.cancelConversation(conversationJid)
                }
            }
        }
        return outcome
    }

    /**
     * Another device's view of a call: answered, declined or deleted there.
     * Group/ad-hoc call events are keyed by era, not callId, so they can't be
     * matched to Photon's group-call rows and are skipped.
     */
    fun processCallEventSync(event: SyncMessage.CallEvent) {
        synchronized(callLock) {
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
     * many members announce it; notifies like a message (so not in a muted
     * group), never rings.
     */
    fun recordGroupCall(
        groupJid: String,
        senderAci: String,
        eraId: String?,
        timestampMs: Long,
    ) {
        messageDb.upsertParticipant(groupJid, senderAci)
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
        if (messageDb.isConversationMuted(groupJid, clock() / 1000)) return
        notifier.showGroupCallStarted(names.title(groupJid, isGroup = true), names.person(senderAci), groupJid)
    }
}
