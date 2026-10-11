package app.photon.signal

import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.internal.push.CallMessage

/**
 * Call log model. Photon has no RingRTC, so it can't answer a Signal call;
 * it only reports calls as rows in the conversation (content_type "call",
 * outcome text in text_body) plus ringing / missed notifications.
 *
 * The row's text is the single source of truth for its state: every text is
 * produced here and parsed back by [parse].
 */
internal enum class CallOutcome { RINGING, MISSED, ANSWERED, DECLINED }

internal data class CallState(val outcome: CallOutcome, val isVideo: Boolean)

/** What an incoming call message or call-event sync says happened. */
internal enum class CallSignal {
    /** A fresh offer: the call is ringing now. */
    OFFER,
    /** An offer delivered too late to still be ringing (device was offline). */
    STALE_OFFER,
    /** The caller hung up (or a callee device was busy) before anyone answered. */
    ENDED_UNANSWERED,
    /** Another of the user's devices answered (Hangup ACCEPTED / sync ACCEPTED). */
    ANSWERED_ELSEWHERE,
    /** Another of the user's devices declined (Hangup DECLINED / sync NOT_ACCEPTED). */
    DECLINED_ELSEWHERE,
    /** Still ringing after [SignalCalls.RING_TIMEOUT_MS]: no hangup or call-event sync reached this device. */
    RING_EXPIRED,
}

internal object SignalCalls {
    const val GROUP_CALL_TEXT = "Group call started"

    // RingRTC treats offers older than this as expired; ringing for one
    // would announce a call that has already given up.
    const val OFFER_MAX_AGE_MS = 120_000L

    // A row still ringing this long after the call started is missed: the
    // caller's app has given up by then, so the hangup just never reached
    // this device.
    const val RING_TIMEOUT_MS = OFFER_MAX_AGE_MS

    fun rowId(peerAci: String, callId: Long): String = "call_${peerAci}_$callId"

    /** One row per group call era, however many members announce joining it. */
    fun groupRowId(groupJid: String, eraId: String): String = "gcall_${groupJid}_$eraId"

    fun text(state: CallState): String {
        val kind = if (state.isVideo) "video call" else "voice call"
        return when (state.outcome) {
            CallOutcome.RINGING -> "Incoming $kind"
            CallOutcome.MISSED -> "Missed $kind"
            CallOutcome.ANSWERED -> kind.replaceFirstChar { it.uppercase() }
            CallOutcome.DECLINED -> "Declined $kind"
        }
    }

    fun parse(text: String?): CallState? {
        if (text == null) return null
        for (outcome in CallOutcome.entries) {
            for (video in listOf(false, true)) {
                val state = CallState(outcome, video)
                if (text(state) == text) return state
            }
        }
        return null
    }

    /** Row texts of a call that is still ringing. */
    val RINGING_TEXTS: List<String> = listOf(false, true).map { text(CallState(CallOutcome.RINGING, it)) }

    /** Missed calls are the only call rows that count as unread. */
    fun isUnread(outcome: CallOutcome): Boolean = outcome == CallOutcome.MISSED

    /**
     * Next outcome for a row, or null when the signal changes nothing. Only
     * an offer creates a row; later signals only move an existing one, and
     * never back to ringing or from answered/declined to missed.
     */
    fun next(current: CallOutcome?, signal: CallSignal): CallOutcome? = when (signal) {
        CallSignal.OFFER -> if (current == null) CallOutcome.RINGING else null
        CallSignal.STALE_OFFER -> if (current == null) CallOutcome.MISSED else null
        CallSignal.ENDED_UNANSWERED -> if (current == CallOutcome.RINGING) CallOutcome.MISSED else null
        CallSignal.ANSWERED_ELSEWHERE ->
            if (current == CallOutcome.RINGING || current == CallOutcome.MISSED) CallOutcome.ANSWERED else null
        CallSignal.DECLINED_ELSEWHERE -> if (current == CallOutcome.RINGING) CallOutcome.DECLINED else null
        CallSignal.RING_EXPIRED -> if (current == CallOutcome.RINGING) CallOutcome.MISSED else null
    }

    /**
     * Map a Hangup to a signal. ACCEPTED/DECLINED/BUSY/NEED_PERMISSION carry
     * the callee device that ended the ringing; Photon never answers, so its
     * own device id there is ignored.
     */
    fun signalForHangup(type: CallMessage.Hangup.Type?, deviceId: Int?, localDeviceId: Int): CallSignal? {
        val byOtherDevice = deviceId != null && deviceId != localDeviceId
        return when (type) {
            null, CallMessage.Hangup.Type.HANGUP_NORMAL -> CallSignal.ENDED_UNANSWERED
            CallMessage.Hangup.Type.HANGUP_ACCEPTED -> if (byOtherDevice) CallSignal.ANSWERED_ELSEWHERE else null
            CallMessage.Hangup.Type.HANGUP_DECLINED -> if (byOtherDevice) CallSignal.DECLINED_ELSEWHERE else null
            CallMessage.Hangup.Type.HANGUP_BUSY,
            CallMessage.Hangup.Type.HANGUP_NEED_PERMISSION ->
                if (deviceId != localDeviceId) CallSignal.ENDED_UNANSWERED else null
        }
    }

    /**
     * An offer is stale when the server held it for longer than RingRTC's
     * expiry before delivering it. [serverDeliveredMs] is 0 when the server
     * didn't send the delivery header; fall back to [nowMs] then.
     */
    fun isStaleOffer(serverReceivedMs: Long?, serverDeliveredMs: Long, nowMs: Long): Boolean {
        if (serverReceivedMs == null || serverReceivedMs <= 0) return false
        val delivered = if (serverDeliveredMs > 0) serverDeliveredMs else nowMs
        return delivered - serverReceivedMs > OFFER_MAX_AGE_MS
    }

    /**
     * Conversation jid for a call-event/call-log sync's conversationId: a
     * 16-byte ACI for 1:1 calls, otherwise the 32-byte GroupV2 identifier,
     * encoded like SignalGroupV2Manager.jidFromMasterKey (URL-safe base64,
     * padded, no wrap — java.util's URL encoder produces the same string).
     */
    fun conversationJidFor(conversationId: ByteArray): String? {
        if (conversationId.isEmpty()) return null
        if (conversationId.size == 16) {
            return (ServiceId.parseOrNull(conversationId) as? ServiceId.ACI)?.toString()
        }
        if (conversationId.size == 32) {
            return java.util.Base64.getUrlEncoder().encodeToString(conversationId)
        }
        return null
    }
}
