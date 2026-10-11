package app.photon.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import app.photon.MainActivity
import app.photon.R
import app.photon.PhotonApp
import app.photon.data.db.PhotonDatabase
import app.photon.data.model.boolean
import app.photon.data.model.int
import app.photon.data.model.Platform
import app.photon.data.model.isPseudoWhatsAppJid
import app.photon.data.model.string
import app.photon.data.repository.ChatRepository
import app.photon.signal.AndroidRecordFix
import app.photon.sms.SmsRepository
import app.photon.signal.SignalConfig
import app.photon.signal.SignalAccountManager
import app.photon.signal.SignalCredentials
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.SignalMessageSender
import app.photon.signal.SignalRepository
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.store.SignalProtocolDatabase
import app.photon.signal.store.PhotonProtocolStore
import app.photon.data.model.WsEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.thread

class PhotonService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var goBridge: GoBridge
    private lateinit var wsClient: WsClient
    private lateinit var chatRepository: ChatRepository
    private var bridgeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("Running"))

        goBridge = GoBridge(this)
        wsClient = WsClient(goBridge)
        val database = PhotonDatabase(this)
        chatRepository = ChatRepository(database, wsClient, scope)

        // Expose as singletons for UI access
        _goBridge = goBridge
        _wsClient = wsClient
        _chatRepository = chatRepository
        _database = database

        // Initialize SMS
        _smsRepository = SmsRepository(this, scope)

        // Initialize Signal components
        AndroidRecordFix.apply()
        SignalConfig.init(this)
        val signalProtocolDb = SignalProtocolDatabase(this)
        _signalProtocolDb = signalProtocolDb
        val signalProtocolStore = PhotonProtocolStore(signalProtocolDb)
        val signalCreds = SignalCredentials(signalProtocolDb)
        _signalCredentials = signalCreds
        _signalManager = SignalAccountManager(signalProtocolStore, signalCreds, signalProtocolDb)
        val signalMessageDb = SignalMessageDatabase(this)
        _signalMessageDb = signalMessageDb
        val signalContacts = app.photon.signal.AndroidContactResolver(this)
        _signalSender = SignalMessageSender(signalCreds, signalProtocolStore, signalMessageDb, signalContacts::resolve)
        _signalRepository = SignalRepository(signalMessageDb, _signalSender, scope)
        _signalReceiver = SignalMessageReceiver(this, signalCreds, signalProtocolStore, signalMessageDb)

        // Start Signal message receiver if already paired
        Log.i("PhotonService", "Signal registered: ${signalCreds.isRegistered()}")
        if (signalCreds.isRegistered()) {
            Log.i("PhotonService", "Starting Signal message receiver")
            _signalReceiver?.start()
        }

        // Signal's ephemeral window: downloaded media TTL plus the message
        // cap/age from Settings → Chat (WhatsApp's is enforced inside the
        // bridge). Its own loop, so it runs even if the Go bridge fails to start.
        scope.launch {
            val prefs = app.photon.data.PhotonPreferences(this@PhotonService)
            while (true) {
                delay(5 * 60_000)
                try {
                    _signalReceiver?.pruneMedia()
                } catch (e: Exception) {
                    Log.w("PhotonService", "Signal media prune failed: ${e.message}")
                }
                try {
                    _signalReceiver?.pruneMessages(prefs.maxMessages.first(), prefs.maxDays.first())
                } catch (e: Exception) {
                    Log.w("PhotonService", "Signal message prune failed: ${e.message}")
                }
            }
        }

        // Subscribed before the bridge starts, so no notification is missed.
        val whatsappEvents = wsClient.subscribeLossless(WHATSAPP_SERVICE_EVENTS)

        // Start the Go bridge and keep it running
        bridgeJob = scope.launch {
            try {
                try {
                    wsClient.connect()
                } catch (e: Exception) {
                    Log.e("PhotonService", "Failed to start Go bridge", e)
                }
                updateNotification()
                // Listen for WhatsApp messages and show notifications
                launch { listenForWhatsAppMessages(whatsappEvents) }
                // Update notification periodically to reflect connection state
                launch {
                    while (true) {
                        delay(10_000)
                        updateNotification()
                    }
                }
                // Ephemeral media sweep (the Go bridge prunes its own dirs;
                // this covers the Kotlin-owned ones).
                launch {
                    while (true) {
                        delay(5 * 60_000)
                        try {
                            pruneStagedSendFiles()
                        } catch (e: Exception) {
                            Log.w("PhotonService", "Media prune failed: ${e.message}")
                        }
                    }
                }
                superviseBridge()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PhotonService", "WhatsApp bridge supervisor failed", e)
            }
        }
    }

    /**
     * Restarts the bridge whenever it exits (crash, low-memory kill), backing
     * off exponentially to a 60s cap so one that dies on startup doesn't spin.
     */
    private suspend fun superviseBridge() {
        val backoff = RestartBackoff()
        while (true) {
            wsClient.isConnected.first { !it }
            val wait = backoff.next()
            Log.w("PhotonService", "WhatsApp bridge not running; restarting in ${wait}ms")
            delay(wait)
            try {
                wsClient.connect()
            } catch (e: Exception) {
                Log.e("PhotonService", "Failed to restart Go bridge", e)
            }
            // A bridge that stays up for a while has recovered.
            if (withTimeoutOrNull(BRIDGE_STABLE_MS) { wsClient.isConnected.first { !it } } == null) {
                backoff.reset()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        bridgeJob?.cancel()
        // Stopping waits for the bridge to exit; keep that off the main thread.
        val client = wsClient
        thread(name = "gobridge-stop") { client.disconnect() }
        _wsClient = null
        _chatRepository = null
        _database = null
        _signalReceiver?.stop()
        _signalSender?.shutdown()
        _signalManager = null
        _signalCredentials = null
        _signalRepository = null
        _signalReceiver = null
        _signalSender = null
        _signalMessageDb = null
        _signalProtocolDb = null
    }

    private suspend fun listenForWhatsAppMessages(events: ReceiveChannel<WsEvent>) {
        events.consumeEach { evt ->
            try {
                handleWhatsAppEvent(evt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One failed event (a DB read during a reset, say) mustn't end
                // notifications for the rest of the session.
                Log.w("PhotonService", "Failed to handle WhatsApp ${evt.type}", e)
            }
        }
    }

    private fun handleWhatsAppEvent(evt: WsEvent) {
        when (evt.type) {
            "new_message" -> {
                if (evt.boolean("is_from_me") == true) return

                val convJid = evt.string("conversation_jid") ?: return
                // Pseudo-chats (status broadcasts etc.) shouldn't ring.
                if (isPseudoWhatsAppJid(convJid)) return

                val conversation = _chatRepository?.getConversation(convJid)
                if (conversation?.isMuted == true) return

                val name = evt.string("sender_name")?.takeIf { it.isNotBlank() }
                    ?: conversation?.name
                    ?: convJid.substringBefore("@")

                NotificationHelper.showMessageNotification(
                    this@PhotonService, name, evt.string("text_body"), Platform.WHATSAPP, convJid,
                )
            }
            "read_self" -> {
                // The user read (or marked read) this chat on another device;
                // the banner shouldn't linger. Plain "receipt" events are the
                // other person reading OUR messages and must not cancel.
                val convJid = evt.string("conversation_jid")
                if (!convJid.isNullOrBlank()) {
                    NotificationHelper.cancelForConversation(this@PhotonService, convJid)
                }
            }
            "message_revoked" -> {
                // A sender deleted the only unread message: withdraw the banner
                // that announced it.
                val convJid = evt.string("conversation_jid")
                if (!convJid.isNullOrBlank() && evt.boolean("was_unread") == true && evt.int("unread_count") == 0) {
                    NotificationHelper.cancelForConversation(this@PhotonService, convJid)
                }
            }
            // Calls ring regardless of mute, as WhatsApp itself does.
            "call_incoming" -> {
                val convJid = evt.string("conversation_jid") ?: return
                NotificationHelper.showIncomingCall(
                    this@PhotonService, callerName(evt, convJid), Platform.WHATSAPP, convJid,
                    isVideo = evt.boolean("is_video") == true,
                )
            }
            "call_ended" -> {
                val convJid = evt.string("conversation_jid") ?: return
                NotificationHelper.cancelIncomingCall(this@PhotonService, convJid)
            }
            "call_missed" -> {
                val convJid = evt.string("conversation_jid") ?: return
                NotificationHelper.showMissedCall(
                    this@PhotonService, callerName(evt, convJid), Platform.WHATSAPP, convJid,
                    isVideo = evt.boolean("is_video") == true,
                )
            }
        }
    }

    private fun callerName(evt: WsEvent, convJid: String): String =
        evt.string("caller_name")?.takeIf { it.isNotBlank() } ?: convJid.substringBefore("@")

    /**
     * Outgoing media staged by MessageInput (voice notes, picked files) lands
     * in filesDir as voice_*.ogg / send_media_* and was never cleaned up by
     * either platform. A 24h TTL (vs the 5min viewed-media TTL) keeps
     * retry-from-file working for recent failed sends.
     */
    private fun pruneStagedSendFiles() {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        filesDir.listFiles()?.forEach { f ->
            if (f.isFile &&
                (f.name.startsWith("voice_") || f.name.startsWith("send_media_")) &&
                f.lastModified() < cutoff
            ) {
                f.delete()
            }
        }
    }

    private var lastNotifiedStatus: String? = null

    private fun updateNotification() {
        val parts = mutableListOf<String>()
        val waState = _wsClient?.whatsappState?.value
        if (waState == "connected") parts.add("WA \u2713") else parts.add("WA \u2717")
        val sigState = _signalReceiver?.state?.value
        if (sigState == "connected") parts.add("Signal \u2713") else if (_signalCredentials?.isRegistered() == true) parts.add("Signal \u2717")
        val status = parts.joinToString("  ")
        // Skip the re-post when nothing changed \u2014 this runs on a 10s timer
        // and each notify() is binder/systemui work that keeps the device
        // from idling.
        if (status == lastNotifiedStatus) return
        lastNotifiedStatus = status
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, PhotonApp.CHANNEL_SERVICE)
            .setContentTitle("Photon")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1

        // Bridge events the notification layer acts on; delivered losslessly.
        private val WHATSAPP_SERVICE_EVENTS = setOf(
            "new_message", "read_self", "message_revoked", "call_incoming", "call_ended", "call_missed",
        )

        // A restarted bridge that stays up this long resets the restart backoff.
        private const val BRIDGE_STABLE_MS = 60_000L

        // Simple service locator — the app is small enough to not need DI.
        // The three repository singletons that the chat-list UIs read are
        // backed by Compose state so that when PhotonService.onCreate
        // populates them (slightly after MainActivity has already composed),
        // the screens reading them are automatically invalidated and rebind
        // to the real flows. Without this, AllChatsScreen would lock onto a
        // null fallback flow until something else triggered recomposition.
        private val _chatRepoState = mutableStateOf<ChatRepository?>(null)
        var _chatRepository: ChatRepository?
            get() = _chatRepoState.value
            set(value) { _chatRepoState.value = value }

        private val _signalRepoState = mutableStateOf<SignalRepository?>(null)
        var _signalRepository: SignalRepository?
            get() = _signalRepoState.value
            set(value) { _signalRepoState.value = value }

        private val _smsRepoState = mutableStateOf<SmsRepository?>(null)
        var _smsRepository: SmsRepository?
            get() = _smsRepoState.value
            set(value) { _smsRepoState.value = value }

        var _wsClient: WsClient? = null
            private set
        var _database: PhotonDatabase? = null
            private set
        var _signalManager: SignalAccountManager? = null
            private set
        var _signalCredentials: SignalCredentials? = null
            private set
        var _signalReceiver: SignalMessageReceiver? = null
            private set
        var _signalSender: SignalMessageSender? = null
            private set
        // Held only so resetSignal can close them before deleting their files.
        private var _signalProtocolDb: SignalProtocolDatabase? = null
        private var _signalMessageDb: SignalMessageDatabase? = null
        /** Lifecycle: set by the receiver when the auth WS comes up; cleared on stop. */
        @Volatile var _signalGroupManager: app.photon.signal.SignalGroupV2Manager? = null
        var _goBridge: GoBridge? = null
            private set

        // QR code cache for WhatsApp pairing
        var cachedQrText: String? = null
            private set
        var cachedQrTimestamp: Long = 0
            private set
        private const val QR_TTL_MS = 15_000L

        fun cacheQrCode(text: String) {
            cachedQrText = text
            cachedQrTimestamp = System.currentTimeMillis()
        }

        fun getCachedQr(): String? {
            val text = cachedQrText ?: return null
            if (System.currentTimeMillis() - cachedQrTimestamp > QR_TTL_MS) {
                cachedQrText = null
                return null
            }
            return text
        }

        fun clearQrCache() {
            cachedQrText = null
        }

        /**
         * Stop the bridge, wipe all WhatsApp data, and start it again.
         * Lives here (not in the settings UI) so the bridge's file layout
         * and the restart choreography have a single owner.
         */
        suspend fun resetWhatsApp(filesDir: java.io.File) {
            val wipe = {
                filesDir.listFiles()?.filter {
                    it.name.startsWith("messages.db") || it.name.startsWith("whatsmeow.db")
                }?.forEach { it.delete() }
                java.io.File(filesDir, "media").deleteRecursively()
                java.io.File(filesDir, "thumbs").deleteRecursively()
                // The Kotlin reader holds a long-lived handle; the deleted inode
                // would keep serving stale rows without this.
                _database?.invalidate()
            }
            // whileStopped keeps the supervisor from restarting the bridge
            // mid-wipe.
            val ws = _wsClient
            if (ws == null) wipe() else ws.whileStopped { wipe() }
        }

        /**
         * Unlink from the Signal account and wipe all Signal state. logout()
         * calls DELETE /v1/devices/{id} so the primary cleanly forgets us —
         * must run off the main thread.
         *
         * The components stay alive and re-pairable: the sender is only
         * invalidated (shutting down its executor broke sends after a
         * re-pair), and the database helpers are closed before their files
         * are deleted, so the next access recreates empty databases instead
         * of writing through a handle to the deleted file.
         */
        fun resetSignal(context: android.content.Context) {
            _signalReceiver?.resetAccountState()
            _signalSender?.invalidate()
            _signalManager?.logout()
            _signalMessageDb?.closeAndDelete(context)
            _signalProtocolDb?.closeAndDelete(context)
            java.io.File(context.filesDir, "signal_media").deleteRecursively()
        }

        val wsClient: WsClient get() = _wsClient!!
    }
}
