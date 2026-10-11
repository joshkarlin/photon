package app.photon.service

import android.content.Context
import android.util.Log
import app.photon.chat.ChatBackend
import app.photon.chat.WhatsAppBackend
import app.photon.data.db.PhotonDatabase
import app.photon.data.model.BridgeEvent
import app.photon.data.model.Platform
import app.photon.data.model.boolean
import app.photon.data.model.int
import app.photon.data.model.isPseudoWhatsAppJid
import app.photon.data.model.string
import app.photon.data.names.LocalContacts
import app.photon.data.repository.ChatRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WhatsApp for as long as the service runs: the bridge process and the
 * client that talks to it, the database the bridge writes, and the
 * repository the UI reads.
 *
 * [start] runs the bridge (restarting it whenever it exits) and posts
 * notifications for what it reports; [stop] ends both; [reset] wipes the
 * account with the bridge certainly stopped.
 */
class WhatsAppSession internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    goBridge: GoBridge,
) {
    constructor(context: Context, scope: CoroutineScope) : this(context, scope, GoBridge(context))

    val bridge = BridgeClient(goBridge)
    val database = PhotonDatabase(context)
    val repository = ChatRepository(database, bridge, scope, LocalContacts.get(context))
    val backend: ChatBackend = WhatsAppBackend(repository, bridge, scope)

    private var job: Job? = null

    /** Start the bridge and keep it running. Starting a running session does nothing. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        // Subscribed before the bridge starts, so no notification is missed.
        val events = bridge.subscribeLossless(NOTIFIED_EVENTS)
        job = scope.launch {
            try {
                launch { notify(events) }
                try {
                    bridge.connect()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start Go bridge", e)
                }
                supervise()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "WhatsApp bridge supervisor failed", e)
            }
        }.also { started -> started.invokeOnCompletion { events.cancel() } }
    }

    /**
     * Stop the bridge and return once it has exited; nothing restarts it.
     * Waits for the bridge, so call it off the main thread.
     */
    suspend fun stop() {
        val running = synchronized(this) { job.also { job = null } }
        // The supervisor goes first, so it can't restart what's stopped next.
        running?.cancelAndJoin()
        bridge.disconnect()
    }

    /**
     * Unlink and wipe all WhatsApp data, then start the bridge again for a
     * new pairing. The supervisor can't restart the bridge mid-wipe, and the
     * reader's long-lived handle to the deleted database is dropped, or it
     * would keep serving the old rows.
     */
    suspend fun reset() {
        bridge.whileStopped {
            context.filesDir.listFiles()?.filter {
                it.name.startsWith("messages.db") || it.name.startsWith("whatsmeow.db")
            }?.forEach { it.delete() }
            File(context.filesDir, "media").deleteRecursively()
            File(context.filesDir, "thumbs").deleteRecursively()
            database.invalidate()
        }
        clearQrCode()
    }

    /**
     * Restarts the bridge whenever it exits (crash, low-memory kill), backing
     * off exponentially to a 60s cap so one that dies on startup doesn't spin.
     */
    private suspend fun supervise() {
        val backoff = RestartBackoff()
        while (true) {
            bridge.isConnected.first { !it }
            val wait = backoff.next()
            Log.w(TAG, "WhatsApp bridge not running; restarting in ${wait}ms")
            delay(wait)
            try {
                bridge.connect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart Go bridge", e)
            }
            // A bridge that stays up for a while has recovered.
            if (withTimeoutOrNull(BRIDGE_STABLE_MS) { bridge.isConnected.first { !it } } == null) {
                backoff.reset()
            }
        }
    }

    // ─── Pairing QR ─────────────────────────────────────────────────
    // The pairing screen's last QR code, so leaving and coming back within
    // its lifetime shows it at once rather than waiting for the next one.

    @Volatile private var qrCode: Pair<String, Long>? = null

    fun cacheQrCode(text: String) {
        qrCode = text to System.currentTimeMillis()
    }

    fun cachedQrCode(): String? {
        val (text, at) = qrCode ?: return null
        if (System.currentTimeMillis() - at > QR_TTL_MS) {
            qrCode = null
            return null
        }
        return text
    }

    fun clearQrCode() {
        qrCode = null
    }

    // ─── Notifications ──────────────────────────────────────────────

    private suspend fun notify(events: ReceiveChannel<BridgeEvent>) {
        events.consumeEach { evt ->
            try {
                handleEvent(evt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One failed event (a DB read during a reset, say) mustn't end
                // notifications for the rest of the session.
                Log.w(TAG, "Failed to handle WhatsApp ${evt.type}", e)
            }
        }
    }

    private fun handleEvent(evt: BridgeEvent) {
        when (evt.type) {
            "new_message" -> {
                if (evt.boolean("is_from_me") == true) return

                val convJid = evt.string("conversation_jid") ?: return
                // Pseudo-chats (status broadcasts etc.) shouldn't ring.
                if (isPseudoWhatsAppJid(convJid)) return

                val conversation = repository.storedConversation(convJid)
                if (conversation?.isMuted == true) return

                val text = repository.names.messageNotification(evt, conversation)
                NotificationHelper.showMessageNotification(
                    context, text.title, text.body, Platform.WHATSAPP, convJid,
                )
            }
            "read_self" -> {
                // The user read (or marked read) this chat on another device;
                // the banner shouldn't linger. Plain "receipt" events are the
                // other person reading OUR messages and must not cancel.
                val convJid = evt.string("conversation_jid")
                if (!convJid.isNullOrBlank()) {
                    NotificationHelper.cancelForConversation(context, convJid)
                }
            }
            "message_revoked" -> {
                // A sender deleted the only unread message: withdraw the banner
                // that announced it.
                val convJid = evt.string("conversation_jid")
                if (!convJid.isNullOrBlank() && evt.boolean("was_unread") == true && evt.int("unread_count") == 0) {
                    NotificationHelper.cancelForConversation(context, convJid)
                }
            }
            // Calls ring regardless of mute, as WhatsApp itself does.
            "call_incoming" -> {
                val convJid = evt.string("conversation_jid") ?: return
                NotificationHelper.showIncomingCall(
                    context, callerName(evt, convJid), Platform.WHATSAPP, convJid,
                    isVideo = evt.boolean("is_video") == true,
                )
            }
            "call_ended" -> {
                val convJid = evt.string("conversation_jid") ?: return
                NotificationHelper.cancelIncomingCall(context, convJid)
            }
            "call_missed" -> {
                val convJid = evt.string("conversation_jid") ?: return
                NotificationHelper.showMissedCall(
                    context, callerName(evt, convJid), Platform.WHATSAPP, convJid,
                    isVideo = evt.boolean("is_video") == true,
                )
            }
        }
    }

    private fun callerName(evt: BridgeEvent, convJid: String): String =
        repository.names.callerTitle(evt, repository.storedConversation(convJid))

    private companion object {
        const val TAG = "PhotonService"

        // Bridge events the notification layer acts on; delivered losslessly.
        val NOTIFIED_EVENTS = setOf(
            "new_message", "read_self", "message_revoked", "call_incoming", "call_ended", "call_missed",
        )

        // A restarted bridge that stays up this long resets the restart backoff.
        const val BRIDGE_STABLE_MS = 60_000L

        const val QR_TTL_MS = 15_000L
    }
}
