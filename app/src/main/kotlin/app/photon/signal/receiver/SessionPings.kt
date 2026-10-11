package app.photon.signal.receiver

import android.content.Context
import android.util.Log
import app.photon.signal.SignalCredentials
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.getSessionPingTargetAcis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = SignalMessageReceiver.TAG

/**
 * Session pings: silent NullMessages that make contacts' clients learn this
 * linked device, so their messages are encrypted for it too.
 *
 * @param sendPing sends one ping to an ACI, returning whether it went out
 */
internal class SessionPings(
    private val context: Context,
    private val credentials: SignalCredentials,
    private val messageDb: SignalMessageDatabase,
    private val scope: CoroutineScope,
    private val sendPing: (aci: String) -> Boolean,
) {
    @Volatile private var activeContactsPinged = false
    @Volatile private var activeContactsPingInFlight = false

    /**
     * Send a NullMessage to each active Signal ACI we know about. Without
     * this, those contacts' apps don't know our linked deviceId exists and
     * won't include us in their encrypted recipient list, so their messages
     * never reach Photon. DM conversations cover direct contacts; GroupV2
     * participants cover group-only members. Once per contact (persisted
     * in SharedPreferences), with a 7-day refresh so sessions don't go stale.
     */
    fun onConnected() {
        if (!activeContactsPinged) {
            activeContactsPinged = true
            scheduleActiveContactPing(8000) // let contacts sync ingest first
        }
    }

    /** Forget who was pinged (Signal reset), so a re-pair pings everyone for the new device id. */
    fun reset() {
        activeContactsPinged = false
        context.getSharedPreferences("signal_ping_state", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    /**
     * Sends a Signal NullMessage to each active ACI we know about (DM
     * conversations plus GroupV2 participants). The recipient processes the
     * NullMessage silently — no notification, no chat entry — but it forces
     * their app to notice our deviceId and add us to their encrypted-recipient
     * list. Their subsequent messages then reach Photon.
     *
     * Tracked per-ACI in SharedPreferences so we ping each contact at most
     * once per refresh window (currently 7 days).
     */
    private suspend fun pingActiveContacts() {
        val myAci = credentials.aciString?.lowercase()
        val prefs = context.getSharedPreferences("signal_ping_state", android.content.Context.MODE_PRIVATE)
        val refreshCutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000

        val candidates = messageDb.getSessionPingTargetAcis()
            .filter { it.lowercase() != myAci }
            .distinct()
        val targets = candidates.filter { prefs.getLong(it, 0L) < refreshCutoff }

        if (targets.isEmpty()) {
            Log.d(
                TAG,
                "No Signal session pings due (candidates=${candidates.size}, deviceId=${credentials.deviceId})",
            )
            return
        }
        Log.i(
            TAG,
            "Pinging ${targets.size}/${candidates.size} active Signal contacts " +
                "to establish deviceId=${credentials.deviceId} sessions",
        )
        for (jid in targets) {
            val ok = sendPing(jid)
            if (ok) {
                prefs.edit().putLong(jid, System.currentTimeMillis()).apply()
                Log.i(TAG, "Session ping succeeded for ${jid.take(8)}…")
            } else {
                Log.w(TAG, "Session ping failed for ${jid.take(8)}…")
            }
            // Stagger so we don't burst the server. 800ms between pings is
            // generous; with ~10 active contacts, total wall time ~8s.
            delay(800)
        }
        Log.i(TAG, "Active-contact pinging complete")
    }

    fun scheduleActiveContactPing(delayMs: Long = 0L) {
        val shouldStart = synchronized(this) {
            if (activeContactsPingInFlight) {
                false
            } else {
                activeContactsPingInFlight = true
                true
            }
        }
        if (!shouldStart) return
        scope.launch {
            try {
                if (delayMs > 0) delay(delayMs)
                pingActiveContacts()
            } catch (e: Exception) {
                Log.w(TAG, "Active contact pinging failed: ${e.message}")
            } finally {
                synchronized(this@SessionPings) {
                    activeContactsPingInFlight = false
                }
            }
        }
    }
}
