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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PhotonService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var goBridge: GoBridge
    private lateinit var wsClient: WsClient
    private lateinit var chatRepository: ChatRepository

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("Running"))

        goBridge = GoBridge(this)
        wsClient = WsClient(goBridge.port)
        chatRepository = ChatRepository(PhotonDatabase(this), wsClient, scope)

        // Expose as singletons for UI access
        _goBridge = goBridge
        _wsClient = wsClient
        _chatRepository = chatRepository
        _database = PhotonDatabase(this)

        // Initialize SMS
        _smsRepository = SmsRepository(this, scope)

        // Initialize Signal components
        AndroidRecordFix.apply()
        SignalConfig.init(this)
        val signalProtocolDb = SignalProtocolDatabase(this)
        val signalProtocolStore = PhotonProtocolStore(signalProtocolDb)
        val signalCreds = SignalCredentials(signalProtocolDb)
        _signalCredentials = signalCreds
        _signalManager = SignalAccountManager(signalProtocolStore, signalCreds, signalProtocolDb)
        val signalMessageDb = SignalMessageDatabase(this)
        _signalSender = SignalMessageSender(signalCreds, signalProtocolStore, signalMessageDb)
        _signalRepository = SignalRepository(signalMessageDb, signalCreds, _signalSender, scope)
        _signalReceiver = SignalMessageReceiver(this, signalCreds, signalProtocolStore, signalMessageDb)

        // Start Signal message receiver if already paired
        Log.i("PhotonService", "Signal registered: ${signalCreds.isRegistered()}")
        if (signalCreds.isRegistered()) {
            Log.i("PhotonService", "Starting Signal message receiver")
            _signalReceiver?.start()
        }

        // Start Go bridge and connect WebSocket
        scope.launch {
            try {
                goBridge.start()
                connectWithRetry()
                updateNotification()
                // Listen for WhatsApp messages and show notifications
                launch { listenForWhatsAppMessages() }
                // Update notification periodically to reflect connection state
                launch {
                    while (true) {
                        delay(10_000)
                        updateNotification()
                    }
                }
                monitorProcess()
            } catch (e: Exception) {
                Log.e("PhotonService", "Failed to start Go bridge", e)
            }
        }
    }

    private suspend fun connectWithRetry() {
        repeat(20) { attempt ->
            delay(500)
            try {
                wsClient.connect()
                Log.i("PhotonService", "WebSocket connected on attempt ${attempt + 1}")
                return
            } catch (e: Exception) {
                Log.d("PhotonService", "WS connect attempt ${attempt + 1} failed: ${e.message}")
            }
        }
        Log.e("PhotonService", "Failed to connect WebSocket after 20 attempts")
    }

    private suspend fun monitorProcess() {
        while (true) {
            delay(5000)
            if (!goBridge.isRunning()) {
                Log.w("PhotonService", "Go bridge process died, restarting...")
                goBridge.start()
                delay(1000)
                wsClient.disconnect()
                connectWithRetry()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        wsClient.disconnect()
        goBridge.stop()
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
    }

    private suspend fun listenForWhatsAppMessages() {
        _wsClient?.events?.collect { evt ->
            when (evt.type) {
                "new_message" -> {
                    val isFromMe = evt.payload["is_from_me"]?.toString()?.trim('"')
                    if (isFromMe == "true") return@collect

                    val convJid = evt.payload["conversation_jid"]?.toString()?.trim('"') ?: return@collect
                    // Skip WhatsApp status-broadcast JIDs ("status@broadcast") and the
                    // server-side "0@" metadata pseudo-chat — they shouldn't ring.
                    if (convJid.startsWith("status@") || convJid.startsWith("0@") ||
                        convJid.contains("@broadcast")) return@collect

                    val conversation = _chatRepository?.getConversation(convJid)
                    if (conversation?.isMuted == true) return@collect

                    val senderName = evt.payload["sender_name"]?.toString()?.trim('"')
                    val body = evt.payload["text_body"]?.toString()?.trim('"')
                    val name = senderName?.takeIf { it.isNotBlank() }
                        ?: conversation?.name
                        ?: convJid.substringBefore("@")

                    NotificationHelper.showMessageNotification(
                        this@PhotonService, name, body, "WhatsApp", convJid,
                    )
                }
                "receipt" -> {
                    // Reading on any linked device fires a read receipt; clear the
                    // Photon notification for that conversation so the banner doesn't
                    // linger after the message has been consumed.
                    val receiptType = evt.payload["type"]?.toString()?.trim('"')
                    if (receiptType == "read") {
                        val convJid = evt.payload["conversation_jid"]?.toString()?.trim('"')
                        if (!convJid.isNullOrBlank()) {
                            NotificationHelper.cancelForConversation(this@PhotonService, convJid)
                        }
                    }
                }
            }
        }
    }

    private fun updateNotification() {
        val parts = mutableListOf<String>()
        val waState = _wsClient?.whatsappState?.value
        if (waState == "connected") parts.add("WA \u2713") else parts.add("WA \u2717")
        val sigState = _signalReceiver?.state?.value
        if (sigState == "connected") parts.add("Signal \u2713") else if (_signalCredentials?.isRegistered() == true) parts.add("Signal \u2717")
        val status = parts.joinToString("  ")
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

        suspend fun restartWhatsApp() {
            _wsClient?.disconnect()
            _goBridge?.stop()
            kotlinx.coroutines.delay(300)
            _goBridge?.start()
            kotlinx.coroutines.delay(500)
            try { _wsClient?.connect() } catch (_: Exception) {}
        }

        val wsClient: WsClient get() = _wsClient!!
        val chatRepository: ChatRepository get() = _chatRepository!!
        val database: PhotonDatabase get() = _database!!
    }
}
