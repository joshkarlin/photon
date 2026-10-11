package app.photon.service

import android.content.Context
import android.util.Log
import app.photon.chat.ChatBackend
import app.photon.chat.SignalBackend
import app.photon.data.PhotonPreferences
import app.photon.data.names.LocalContacts
import app.photon.signal.AndroidRecordFix
import app.photon.signal.SignalAccountManager
import app.photon.signal.SignalConfig
import app.photon.signal.SignalCredentials
import app.photon.signal.SignalGroupV2Manager
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.SignalMessageSender
import app.photon.signal.SignalRepository
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.store.PhotonProtocolStore
import app.photon.signal.store.SignalProtocolDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Signal for as long as the service runs: the account's credentials and
 * protocol store, the message database, and the account manager, sender,
 * receiver and repository built on them.
 *
 * [start] connects when the device is linked and runs the ephemeral-window
 * prune; [stop] ends both; [reset] unlinks and wipes the account while
 * keeping every component usable for the next link.
 */
class SignalSession(private val context: Context, private val scope: CoroutineScope) {

    init {
        AndroidRecordFix.apply()
        SignalConfig.init(context)
    }

    private val protocolDb = SignalProtocolDatabase(context)
    private val protocolStore = PhotonProtocolStore(protocolDb)
    private val messageDb = SignalMessageDatabase(context)

    val credentials = SignalCredentials(protocolDb)
    val accountManager = SignalAccountManager(protocolStore, credentials, protocolDb)
    val sender: SignalMessageSender = SignalMessageSender(
        credentials, protocolStore, messageDb,
        groupManager = { receiver.groupManager },
    )
    val receiver = SignalMessageReceiver(context, credentials, protocolStore, messageDb, sender)
    val repository = SignalRepository(messageDb, sender, scope, LocalContacts.get(context)) { credentials.aciString }
    val backend: ChatBackend = SignalBackend(repository, receiver.state, sender.identityStore, receiver::downloadAttachment)

    /** The receiver's GroupV2 manager, while it's connected. */
    val groupManager: SignalGroupV2Manager? get() = receiver.groupManager

    private var pruneJob: Job? = null

    /** Connect if linked, and keep the ephemeral window. Starting a running session does nothing. */
    @Synchronized
    fun start() {
        Log.i(TAG, "Signal registered: ${credentials.isRegistered()}")
        if (credentials.isRegistered()) {
            Log.i(TAG, "Starting Signal message receiver")
            receiver.start()
        }
        if (pruneJob?.isActive != true) pruneJob = scope.launch { pruneEvery5Minutes() }
    }

    @Synchronized
    fun stop() {
        pruneJob?.cancel()
        pruneJob = null
        receiver.stop()
        sender.shutdown()
    }

    /** Drop the connection and open a new one (Settings → Refresh connection). */
    fun refresh() {
        receiver.stop()
        receiver.start()
    }

    /** The device was just linked: start receiving. */
    fun onLinked() {
        receiver.start()
    }

    /**
     * Unlink from the Signal account and wipe all Signal state. The logout
     * asks the server to forget this device (DELETE /v1/devices/{id}), so
     * it blocks on the network: call it off the main thread.
     *
     * Every component stays alive and re-pairable: the sender is only
     * invalidated (shutting down its executor broke sends after a re-pair),
     * and the database helpers are closed before their files are deleted,
     * so the next access recreates empty databases instead of writing
     * through a handle to the deleted file.
     */
    fun reset() {
        receiver.resetAccountState()
        sender.invalidate()
        accountManager.logout()
        messageDb.closeAndDelete(context)
        protocolDb.closeAndDelete(context)
        File(context.filesDir, "signal_media").deleteRecursively()
    }

    // Signal's ephemeral window: downloaded media TTL plus the message
    // cap/age from Settings → Chat (WhatsApp's is enforced inside the bridge).
    private suspend fun pruneEvery5Minutes() {
        val prefs = PhotonPreferences(context)
        while (true) {
            delay(5 * 60_000)
            try {
                receiver.pruneMedia()
            } catch (e: Exception) {
                Log.w(TAG, "Signal media prune failed: ${e.message}")
            }
            try {
                receiver.pruneMessages(prefs.maxMessages.first(), prefs.maxDays.first())
            } catch (e: Exception) {
                Log.w(TAG, "Signal message prune failed: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "PhotonService"
    }
}
