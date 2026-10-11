package app.photon.signal

import android.util.Base64
import android.util.Log
import app.photon.data.names.LocalContacts
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.pruneMessages
import app.photon.signal.receiver.AndroidSignalNotifier
import app.photon.signal.receiver.Attachments
import app.photon.signal.receiver.CallHandler
import app.photon.signal.receiver.ContactNames
import app.photon.signal.receiver.ContactsSync
import app.photon.signal.receiver.DataMessageHandler
import app.photon.signal.receiver.EnvelopeProcessor
import app.photon.signal.receiver.GroupMetadata
import app.photon.signal.receiver.MessageNotifications
import app.photon.signal.receiver.NetworkMonitor
import app.photon.signal.receiver.PreKeyRefill
import app.photon.signal.receiver.Receipts
import app.photon.signal.receiver.RemoteEdits
import app.photon.signal.receiver.SessionPings
import app.photon.signal.receiver.SignalNotifier
import app.photon.signal.receiver.SyncMessageHandler
import app.photon.signal.storage.LibraryStorageReader
import app.photon.signal.storage.StorageMuteSync
import app.photon.signal.store.PhotonProtocolStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.signal.libsignal.metadata.certificate.CertificateValidator
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.whispersystems.signalservice.api.crypto.SignalGroupSessionBuilder
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.internal.push.PushServiceSocket

/**
 * Signal's receive side: one connection's read loop, and everything that
 * handles what arrives on it.
 *
 * @param sender the account's sender: it shares this receiver's sockets and
 *   sends the pings, retry receipts and contacts request the receiver needs
 */
class SignalMessageReceiver(
    private val context: android.content.Context,
    private val credentials: SignalCredentials,
    private val protocolStore: PhotonProtocolStore,
    private val messageDb: SignalMessageDatabase,
    private val sender: SignalMessageSender,
) {
    companion object {
        // Shared by the handlers in app.photon.signal.receiver, so one logcat
        // filter (docs/VERIFY.md) still catches everything the receiver does.
        internal const val TAG = "SignalMessageReceiver"
        // SignalWebSocket exposes inbound frames through a blocking long-poll
        // API. Its Object.wait() releases the socket monitor, so outbound
        // MessageApi/KeysApi requests can proceed while this waits.
        private const val MESSAGE_POLL_TIMEOUT_MS = 30_000L
        // How long a Signal reset waits for in-flight receiver work before
        // the databases are deleted.
        private const val RESET_DRAIN_TIMEOUT_MS = 10_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val config = SignalConfig.createConfiguration()
    private val names = SignalNames(messageDb, LocalContacts.get(context)::nameFor) { credentials.aciString }
    private val notifier: SignalNotifier = AndroidSignalNotifier(context)
    // Read syncs that arrived before their message (see EarlyReadCache).
    private val earlyReads = EarlyReadCache()
    // Envelope processing and PNI merges both rewrite conversations: one at a time.
    private val storeLock = Any()
    private val preKeyRefill = PreKeyRefill(protocolStore)
    private val sessionPings = SessionPings(context, credentials, messageDb, scope, sender::sendSessionPing)
    private val contactNames: ContactNames = ContactNames(
        messageDb, storeLock, notifier,
        profileFetcher = { profileFetcher },
        // Runs only after construction, once calls is set.
        onMerged = { calls.expireStaleRinging() },
    )
    private val groupMetadata = GroupMetadata(messageDb, scope, { groupManager }, sessionPings)
    private val attachments = Attachments(context, messageDb) { pushSocket }
    private val contactsSync = ContactsSync(
        context, messageDb,
        pushSocket = { pushSocket },
        onSynced = { contactNames.mergeResolvableServiceIds() },
    )
    private val calls: CallHandler = CallHandler(
        credentials, messageDb, contactNames, names, notifier,
        schedule = { delayMs, task ->
            scope.launch {
                delay(delayMs)
                task()
            }
        },
    )
    // Chat mutes from the storage service, read over the live connection.
    private val storageMutes = StorageMuteSync(
        credentials, messageDb, scope,
        reader = {
            val pair = sockets
            val ps = pushSocket
            if (pair != null && ps != null) LibraryStorageReader(pair.auth, ps) else null
        },
        requestKeys = sender::requestKeysSync,
    )
    private val receipts = Receipts(credentials, messageDb)
    private val messageNotifications = MessageNotifications(messageDb, names, notifier)
    private val remoteEdits = RemoteEdits(messageDb, messageNotifications)
    private val dataMessages = DataMessageHandler(
        messageDb, contactNames, groupMetadata, calls, remoteEdits, attachments, earlyReads,
        messageNotifications, scope,
    )
    private val syncMessages = SyncMessageHandler(
        credentials, protocolStore.identityStore, messageDb, contactNames, groupMetadata,
        calls, remoteEdits, contactsSync, earlyReads, notifier, storageMutes, scope,
    )
    private val sessionLock = SignalConfig.newSessionLock()
    private val envelopeProcessor = EnvelopeProcessor(
        storeLock,
        SignalGroupSessionBuilder(sessionLock, GroupSessionBuilder(protocolStore)),
        receipts, dataMessages, syncMessages, calls, sender,
    )

    // A validated network ends a reconnect backoff early.
    private val networkReturn = NetworkReturn()
    private val networkMonitor = NetworkMonitor(context) { networkReturn.networkValidated() }

    private val _state = MutableStateFlow("disconnected")
    val state: StateFlow<String> = _state

    // Lifecycle. Every start() begins a new generation and a loop only runs
    // while its generation is current, so "Refresh connection" (stop();
    // start()) can never leave an old loop reconnecting next to the new one.
    // Guarded by lifecycleLock; @Volatile for the lock-free isCurrent() reads.
    private val lifecycleLock = Any()
    @Volatile private var running = false
    @Volatile private var generation = 0L
    private var receiveJob: Job? = null
    @Volatile private var lastMaintenanceAt = 0L

    /**
     * One connection attempt's socket pair. [alive] gates both sockets'
     * canConnect: SignalWebSocket transparently reopens a closed connection on
     * the next request, so anything still holding a superseded socket (an
     * in-flight profile fetch, a late ack) would otherwise open a second
     * authenticated connection and displace the live one.
     */
    private class SocketPair(
        val auth: SignalWebSocket.AuthenticatedWebSocket,
        val unauth: SignalWebSocket.UnauthenticatedWebSocket,
        val alive: AtomicBoolean,
    )

    @Volatile private var sockets: SocketPair? = null
    @Volatile private var profileFetcher: SignalProfileFetcher? = null
    @Volatile private var pushSocket: PushServiceSocket? = null

    /**
     * The GroupV2 fetcher over the current connection's sockets, or null
     * while disconnected. The sender uses it too, so a first send into a
     * group doesn't rebuild the credential cache.
     */
    @Volatile var groupManager: SignalGroupV2Manager? = null
        private set
    @Volatile private var contactsSyncRequested = false

    fun start() {
        synchronized(lifecycleLock) {
            if (running) return
            running = true
            networkMonitor.start()
            val gen = ++generation
            // A fresh start (including "Refresh connection") re-runs upkeep.
            lastMaintenanceAt = 0L
            val previous = receiveJob
            receiveJob = scope.launch {
                // stop() cancelled the previous loop and closed its socket, so
                // it exits promptly; wait for it so two loops never overlap.
                // NonCancellable: if this job is itself stopped while waiting,
                // the next start() joins it and must still wait for `previous`.
                withContext(NonCancellable) { previous?.join() }
                receiveLoop(gen)
            }
        }
        // Calls left ringing when the process stopped.
        scope.launch {
            try {
                calls.expireStaleRinging()
            } catch (e: Exception) {
                Log.w(TAG, "Ring expiry sweep failed: ${e.message}")
            }
        }
        // PNI-keyed conversations whose ACI a past contacts sync revealed.
        scope.launch {
            try {
                contactNames.mergeResolvableServiceIds()
            } catch (e: Exception) {
                Log.w(TAG, "PNI conversation merge failed: ${e.message}")
            }
        }
    }

    fun stop() {
        stopInternal()
    }

    /** Stop and return the cancelled receive loop's job. */
    private fun stopInternal(): Job? {
        val pair: SocketPair?
        val loop: Job?
        synchronized(lifecycleLock) {
            running = false
            generation++
            loop = receiveJob
            loop?.cancel()
            pair = sockets
            sockets = null
            profileFetcher = null
            groupManager = null
            _state.value = "disconnected"
            // Under the lock, or a start() in between keeps the old callback
            // and this stop() then removes the new loop's.
            networkMonitor.stop()
        }
        closeSockets(pair)
        return loop
    }

    /**
     * Forget per-account session state once the device is unlinked (Signal
     * reset), so a re-pair starts clean: ask the primary for contacts again,
     * re-ping every contact for the new device id, and drop held envelopes,
     * early reads and the group credential cache.
     *
     * Blocks (call off the main thread) until in-flight receiver work — the
     * envelope batch being processed, contacts ingest, profile fetches — has
     * finished or been cancelled, so none of it writes the old account's data
     * into the databases the caller is about to delete.
     */
    fun resetAccountState() {
        stopInternal()
        val work = scope.coroutineContext[Job]?.children?.toList().orEmpty()
        work.forEach { it.cancel() }
        val drained = runBlocking {
            withTimeoutOrNull(RESET_DRAIN_TIMEOUT_MS) { work.forEach { it.join() } } != null
        }
        if (!drained) Log.w(TAG, "Reset: receiver work still running after ${RESET_DRAIN_TIMEOUT_MS}ms")
        contactsSyncRequested = false
        storageMutes.reset()
        sessionPings.reset()
        earlyReads.clear()
        envelopeProcessor.clearHeld()
        SignalGroupV2Manager.clearCredentialCache()
    }

    private fun isCurrent(gen: Long): Boolean = running && generation == gen

    /** Publish a connection state, unless this loop has been superseded. */
    private fun setState(gen: Long, value: String) {
        synchronized(lifecycleLock) {
            if (isCurrent(gen)) _state.value = value
        }
    }

    private fun closeSockets(pair: SocketPair?) {
        if (pair == null) return
        pair.alive.set(false)
        sender.detachReceiverWebSockets(pair.auth, pair.unauth)
        try { pair.auth.disconnect() } catch (_: Exception) {}
        try { pair.unauth.disconnect() } catch (_: Exception) {}
    }

    /** When this attempt's socket was first seen connected (0 = not yet). */
    private class Attempt {
        var connectedAt = 0L
    }

    private suspend fun receiveLoop(gen: Long) {
        var failures = 0
        var authFailures = 0
        while (isCurrent(gen)) {
            var ws: SignalWebSocket.AuthenticatedWebSocket? = null
            val attempt = Attempt()
            val networkEpoch = networkReturn.epoch
            try {
                setState(gen, "connecting")
                ws = connectWebSocket(gen)
                // connect() is asynchronous: report "connected" only once the
                // handshake has actually completed.
                markConnectedIfLive(gen, ws, attempt)
                Log.i(TAG, "WebSocket opened, reading messages... stateSnapshot=${ws.stateSnapshot}")
                readMessages(gen, ws, attempt)
            } catch (e: Exception) {
                if (isCurrent(gen)) Log.e(TAG, "Message receive error", e)
            }
            if (!isCurrent(gen)) return

            val wsState = ws?.stateSnapshot
            if (wsState == org.whispersystems.signalservice.api.websocket.WebSocketConnectionState.AUTHENTICATION_FAILED) {
                authFailures++
                if (authFailures >= SignalReconnect.AUTH_FAILURES_BEFORE_LOGOUT) {
                    Log.e(TAG, "Signal server rejected our credentials (HTTP 401/403) $authFailures times. " +
                        "This usually means the device was unlinked on the primary — stopping until " +
                        "Photon is re-paired from Settings → Connections → Signal → RESET.")
                    markLoggedOut(gen)
                    return
                }
            } else {
                authFailures = 0
            }

            failures = SignalReconnect.nextFailureCount(
                failures, attempt.connectedAt.takeIf { it > 0 }?.let { System.currentTimeMillis() - it },
            )
            val wait = SignalReconnect.delayMs(failures)
            setState(gen, "disconnected")
            Log.i(TAG, "Reconnecting in ${wait / 1000}s (attempt $failures, last state: $wsState)")
            val networkReturned = networkReturn.await(wait, networkEpoch)
            if (networkReturned) Log.i(TAG, "Network validated; reconnecting now")
            failures = NetworkReturn.failuresAfterWait(failures, networkReturned)
        }
    }

    /**
     * Credentials rejected: stop reconnecting and report "logged_out" (shown
     * as NOT LINKED in settings). "Refresh connection" or a re-pair starts a
     * new generation.
     */
    private fun markLoggedOut(gen: Long) {
        val pair: SocketPair?
        synchronized(lifecycleLock) {
            if (generation != gen) return
            running = false
            pair = sockets
            sockets = null
            profileFetcher = null
            groupManager = null
            _state.value = "logged_out"
            networkMonitor.stop()
        }
        closeSockets(pair)
    }

    private fun markConnectedIfLive(gen: Long, ws: SignalWebSocket.AuthenticatedWebSocket, attempt: Attempt) {
        if (attempt.connectedAt > 0) return
        if (ws.stateSnapshot != org.whispersystems.signalservice.api.websocket.WebSocketConnectionState.CONNECTED) return
        attempt.connectedAt = System.currentTimeMillis()
        setState(gen, "connected")
    }

    private fun connectWebSocket(gen: Long): SignalWebSocket.AuthenticatedWebSocket {
        // Close the previous attempt's pair first — both sockets; the
        // unauthenticated one used to leak on every reconnect.
        val oldPair = synchronized(lifecycleLock) { sockets.also { sockets = null } }
        closeSockets(oldPair)

        val factory = SignalConfig.webSocketFactory(config, "photon-recv", credentials) { status ->
            Log.w(TAG, "Message error: $status")
        }

        // canConnect is per generation and per pair: a superseded socket
        // must never silently reconnect itself.
        val alive = AtomicBoolean(true)
        val canConnect = { isCurrent(gen) && alive.get() }
        val ws = SignalWebSocket.AuthenticatedWebSocket(
            factory,
            canConnect,
            SignalConfig.sleepTimer,
            30_000L,
        )
        ws.connect()
        ws.registerKeepAliveToken("PhotonReceiver")

        // Unauthenticated socket is used by profile fetches and key uploads.
        val unauthFactory = SignalConfig.webSocketFactory(config, "photon-recv-unauth", null)
        val unauthWs = SignalWebSocket.UnauthenticatedWebSocket(
            unauthFactory, canConnect,
            SignalConfig.sleepTimer,
            30_000L,
        )
        unauthWs.connect()
        unauthWs.registerKeepAliveToken("PhotonReceiverUnauth")
        val pair = SocketPair(ws, unauthWs, alive)

        // Used by the contacts sync to download the encrypted blob from CDN.
        val ps = PushServiceSocket(config, credentials, SignalConfig.USER_AGENT, false)
        val fetcher = SignalProfileFetcher(config, ws, unauthWs, messageDb, scope)
        // GroupV2 fetcher — reuses the same auth WS / push socket.
        val gm = SignalGroupV2Manager(config, credentials, ws, ps)

        // Publish everything under the lock together with the generation
        // check, so a concurrent stop() can't be followed by this attempt
        // re-attaching sockets it has just closed.
        val current = synchronized(lifecycleLock) {
            isCurrent(gen).also { current ->
                if (!current) return@also
                sockets = pair
                pushSocket = ps
                profileFetcher = fetcher
                groupManager = gm
                // Sending sync messages through a second authenticated
                // WebSocket can cause Signal to close this receiver socket.
                // Share the receiver pair so reads and sends are multiplexed
                // over one linked-device session.
                sender.attachReceiverWebSockets(ws, unauthWs)
            }
        }
        if (!current) {
            closeSockets(pair)
            throw IllegalStateException("Receiver stopped while connecting")
        }

        // Linked devices don't have native access to the user's address book.
        // Ask the primary for it once per process so any contact whose Signal
        // profile is anonymous still shows up as the locally-saved name.
        // Throttled to a single request per session — repeating on every WS
        // reconnect would re-download the same blob every ~30s.
        if (!contactsSyncRequested) {
            contactsSyncRequested = true
            scope.launch {
                try {
                    delay(1500)   // let the WS settle first
                    sender.requestContactsSync()
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't request contacts sync: ${e.message}")
                }
            }
        }

        sessionPings.onConnected()

        val now = System.currentTimeMillis()
        if (SignalReconnect.maintenanceDue(lastMaintenanceAt, now)) {
            // Only a round trip that actually reached the server uses up the
            // slot; an attempt made while offline mustn't defer upkeep for
            // the connection that follows it.
            if (runConnectMaintenance(ws, unauthWs)) lastMaintenanceAt = now
        }
        return ws
    }

    /**
     * Upkeep after a (re)connect, throttled to once per
     * [SignalReconnect.MAINTENANCE_INTERVAL_MS] so a flapping connection
     * doesn't redo it on every attempt. Returns whether the pre-key check
     * reached the server.
     */
    private fun runConnectMaintenance(
        ws: SignalWebSocket.AuthenticatedWebSocket,
        unauthWs: SignalWebSocket.UnauthenticatedWebSocket,
    ): Boolean {
        scope.launch { contactNames.sweepProfiles() }
        scope.launch { groupMetadata.refreshGroupsNeedingMetadata() }
        storageMutes.onConnected()

        // Upload one-time pre-keys if they haven't been uploaded yet
        return preKeyRefill.uploadPreKeysIfNeeded(ws, unauthWs)
    }

    private fun readMessages(gen: Long, ws: SignalWebSocket.AuthenticatedWebSocket, attempt: Attempt) {
        val aci = credentials.aci ?: return
        val localAddress = SignalServiceAddress(aci)
        val deviceId = credentials.deviceId
        Log.i(TAG, "Reading as aci=$aci deviceId=$deviceId — other users must have a session " +
            "with this deviceId for their messages to reach us.")

        // Certificate validator for sealed sender. Signal has TWO production
        // trust roots in current builds — server certs are signed by whichever
        // is the active root at the time of issuance. Including both lets us
        // accept legitimate certs regardless of which root signed them.
        val certificateValidator = try {
            val trustRoots = SignalConfig.UNIDENTIFIED_SENDER_TRUST_ROOTS.map { encoded ->
                ECPublicKey(Base64.decode(encoded, Base64.DEFAULT))
            }
            CertificateValidator(trustRoots)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create certificate validator, sealed sender may fail", e)
            null
        }

        val cipher = SignalServiceCipher(
            localAddress, deviceId, protocolStore, sessionLock, certificateValidator,
        )

        while (isCurrent(gen)) {
            try {
                ws.readMessageBatch(MESSAGE_POLL_TIMEOUT_MS, 10) { envelopes ->
                    for (envelope in envelopes) envelopeProcessor.processEnvelope(envelope, cipher, ws)
                }
            } catch (_: java.util.concurrent.TimeoutException) {
                // No traffic within the poll window: loop.
            } catch (e: Exception) {
                Log.e(TAG, "Error reading messages", e)
                throw e
            }
            markConnectedIfLive(gen, ws, attempt)
            envelopeProcessor.retryHeldEnvelopes(cipher, ws)
        }
    }

    /** Download an incoming attachment by message id; see [Attachments.downloadAttachment]. */
    fun downloadAttachment(messageId: String): String? = attachments.downloadAttachment(messageId)

    /** Delete downloaded media past its TTL. Called periodically by the Signal session. */
    fun pruneMedia() = attachments.pruneMedia()

    /**
     * Enforce the ephemeral message window (per-chat cap and max age from
     * Settings → Chat). Called periodically by the Signal session alongside
     * [pruneMedia]; see SignalMessageDatabase.pruneMessages for the policy.
     */
    fun pruneMessages(maxPerConversation: Int, maxAgeDays: Int) {
        val pruned = messageDb.pruneMessages(maxPerConversation, maxAgeDays)
        if (pruned > 0) {
            Log.i(TAG, "Pruned $pruned Signal messages (cap=$maxPerConversation/chat, maxAge=${maxAgeDays}d)")
        }
    }

}
