package app.photon.signal

import android.util.Base64
import android.util.Log
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.receiver.AndroidSignalNotifier
import app.photon.signal.receiver.CallHandler
import app.photon.signal.receiver.Receipts
import app.photon.signal.receiver.Attachments
import app.photon.signal.receiver.ContactsSync
import app.photon.signal.receiver.DataMessageHandler
import app.photon.signal.receiver.RemoteEdits
import app.photon.signal.receiver.SyncMessageHandler
import app.photon.signal.receiver.ContactNames
import app.photon.signal.receiver.GroupMetadata
import app.photon.signal.receiver.PreKeyRefill
import app.photon.signal.receiver.SessionPings
import app.photon.signal.receiver.SignalNotifier
import app.photon.signal.store.PhotonProtocolStore
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
import org.signal.libsignal.metadata.ProtocolDuplicateMessageException
import org.signal.libsignal.metadata.ProtocolException
import org.signal.libsignal.metadata.certificate.CertificateValidator
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.api.crypto.SignalGroupSessionBuilder
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher
import org.whispersystems.signalservice.api.messages.EnvelopeResponse
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.Envelope
import java.util.concurrent.atomic.AtomicBoolean

class SignalMessageReceiver(
    private val context: android.content.Context,
    private val credentials: SignalCredentials,
    private val protocolStore: PhotonProtocolStore,
    private val messageDb: SignalMessageDatabase,
) {
    companion object {
        // Shared by the handlers in app.photon.signal.receiver, so one logcat
        // filter (docs/VERIFY.md) still catches everything the receiver does.
        internal const val TAG = "SignalMessageReceiver"
        // SignalWebSocket exposes inbound frames through a blocking long-poll
        // API. Its Object.wait() releases the socket monitor, so outbound
        // MessageApi/KeysApi requests can proceed while this waits.
        private const val MESSAGE_POLL_TIMEOUT_MS = 30_000L
        // In-process retry delay for an envelope left un-acked after a local
        // processing failure (see retryHeldEnvelopes).
        private const val HELD_ENVELOPE_RETRY_MS = 10_000L
        // How long a Signal reset waits for in-flight receiver work before
        // the databases are deleted.
        private const val RESET_DRAIN_TIMEOUT_MS = 10_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val config = SignalConfig.createConfiguration()
    private val contactResolver = AndroidContactResolver(context)
    private val notifier: SignalNotifier = AndroidSignalNotifier(context)
    // Read syncs that arrived before their message (see EarlyReadCache).
    private val earlyReads = EarlyReadCache()
    private val preKeyRefill = PreKeyRefill(protocolStore)
    private val sessionPings = SessionPings(context, credentials, messageDb, scope)
    private val contactNames = ContactNames(messageDb, contactResolver) { profileFetcher }
    private val groupMetadata = GroupMetadata(messageDb, contactResolver, scope, { groupManager }, sessionPings)
    private val attachments = Attachments(context, messageDb) { pushSocket }
    private val contactsSync = ContactsSync(context, messageDb, contactResolver) { pushSocket }
    private val calls = CallHandler(credentials, messageDb, contactNames, groupMetadata, notifier)
    private val receipts = Receipts(credentials, messageDb)
    private val remoteEdits = RemoteEdits(messageDb, notifier)
    private val dataMessages = DataMessageHandler(
        messageDb, contactNames, groupMetadata, calls, remoteEdits, attachments, earlyReads, notifier, scope,
    )
    private val syncMessages = SyncMessageHandler(
        credentials, protocolStore.identityStore, messageDb, contactResolver, contactNames, groupMetadata,
        calls, remoteEdits, contactsSync, earlyReads, notifier, scope,
    )
    private val sessionLock = SignalConfig.newSessionLock()
    private val senderKeySessionBuilder = SignalGroupSessionBuilder(
        sessionLock,
        GroupSessionBuilder(protocolStore),
    )

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
    @Volatile private var groupManager: SignalGroupV2Manager? = null
    @Volatile private var contactsSyncRequested = false

    // Envelopes left un-acked after a local processing failure.
    private val envelopeRetries = EnvelopeRetryTracker<DecryptedEnvelope>()
    // Their in-process retries, run by the read loop on its own thread.
    private class HeldRetry(
        val response: EnvelopeResponse,
        val socket: SignalWebSocket.AuthenticatedWebSocket,
        val dueAt: Long,
    )
    private val heldRetries = mutableListOf<HeldRetry>()

    fun start() {
        synchronized(lifecycleLock) {
            if (running) return
            running = true
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
    }

    /**
     * Drop the Android-contacts cache and re-resolve every conversation
     * name from scratch. Called after Photon's contact editor writes a new
     * entry so the chat title updates immediately instead of waiting for
     * the next 30-second WS reconnect to trigger the routine refresh.
     */
    fun refreshContactNames() {
        scope.launch {
            try {
                val n = contactNames.reresolveFromLocalContacts()
                if (n > 0) Log.i(TAG, "Live refresh: re-resolved $n conversation names")
            } catch (e: Exception) {
                Log.w(TAG, "Live name refresh failed: ${e.message}")
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
        }
        app.photon.service.PhotonService._signalGroupManager = null
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
        sessionPings.reset()
        earlyReads.clear()
        envelopeRetries.clearAll()
        synchronized(heldRetries) { heldRetries.clear() }
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
        app.photon.service.PhotonService._signalSender?.detachReceiverWebSockets(pair.auth, pair.unauth)
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
            delay(wait)
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
        }
        app.photon.service.PhotonService._signalGroupManager = null
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
        // GroupV2 fetcher — reuses the same auth WS / push socket. Shared
        // via PhotonService so the sender can also consult it when sending
        // into a group without rebuilding the credential cache.
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
                app.photon.service.PhotonService._signalGroupManager = gm
                // Sending sync messages through a second authenticated
                // WebSocket can cause Signal to close this receiver socket.
                // Share the receiver pair so reads and sends are multiplexed
                // over one linked-device session.
                app.photon.service.PhotonService._signalSender?.attachReceiverWebSockets(ws, unauthWs)
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
                    app.photon.service.PhotonService._signalSender?.requestContactsSync()
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

        // Refresh conversation names from this device's local contacts.
        // Cheap: ~50 PhoneLookup queries (cached per session via the
        // resolver) plus a DB write only when the resolved name differs from
        // what's stored. A contact added in the LP3 address book is picked up
        // here; Photon's own contact editor refreshes immediately instead
        // (refreshContactNames).
        scope.launch {
            try {
                val n = contactNames.reresolveFromLocalContacts()
                if (n > 0) Log.i(TAG, "Re-resolved $n conversation names from local contacts")
            } catch (e: Exception) {
                Log.w(TAG, "Conversation name re-resolution failed: ${e.message}")
            }
        }

        scope.launch { groupMetadata.refreshGroupsNeedingMetadata() }

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
                    for (envelope in envelopes) processEnvelope(envelope, cipher, ws)
                }
            } catch (_: java.util.concurrent.TimeoutException) {
                // No traffic within the poll window: loop.
            } catch (e: Exception) {
                Log.e(TAG, "Error reading messages", e)
                throw e
            }
            markConnectedIfLive(gen, ws, attempt)
            retryHeldEnvelopes(cipher, ws)
        }
    }

    /** A decrypted envelope, held across redeliveries if processing fails. */
    private class DecryptedEnvelope(val content: Content, val metadata: EnvelopeMetadata)

    /**
     * Decrypt, store, then ack. The ack tells the server to delete the
     * envelope, so it only goes out once the content is safely processed, or
     * when retrying can't help (decrypt failures, duplicates). If local
     * processing throws, the envelope is left un-acked for the server to
     * redeliver, with the decrypted payload held for that redelivery (see
     * [EnvelopeRetryTracker]) and an in-process retry queued; a poison
     * envelope is acked after 3 failures. Reprocessing is safe: inserts
     * dedupe on the (author, timestamp) key.
     */
    private fun processEnvelope(
        envelopeResponse: EnvelopeResponse,
        cipher: SignalServiceCipher,
        ws: SignalWebSocket.AuthenticatedWebSocket,
    ) {
        val envelope = envelopeResponse.envelope
        val retryKey = envelopeRetryKey(envelope)
        var decrypted: DecryptedEnvelope? = envelopeRetries.pending(retryKey)
        val ack = try {
            if (decrypted == null) {
                val result = cipher.decrypt(envelope, envelopeResponse.serverDeliveredTimestamp)
                if (result == null) {
                    receipts.processContentlessEnvelope(envelope)
                } else {
                    decrypted = DecryptedEnvelope(result.content, result.metadata)
                }
            } else {
                Log.i(TAG, "Reprocessing held envelope ${retryKey.take(12)}… (decrypted on an earlier delivery)")
            }
            decrypted?.let { processContent(it.content, it.metadata, envelopeResponse) }
            envelopeRetries.clear(retryKey)
            true
        } catch (e: org.signal.libsignal.metadata.SelfSendException) {
            Log.d(TAG, "Self-send exception (normal for sync), acking")
            true
        } catch (e: ProtocolDuplicateMessageException) {
            Log.d(TAG, "Duplicate message, acking")
            true
        } catch (e: ProtocolException) {
            Log.w(
                TAG,
                "Protocol decrypt failed (type=${envelope.type}, sender=${e.sender}, " +
                    "senderDevice=${e.senderDevice}, clientTs=${envelope.clientTimestamp}): " +
                    "${e.javaClass.simpleName}: ${e.message}",
            )
            maybeSendRetryReceipt(e, envelopeResponse)
            true
        } catch (e: org.signal.libsignal.protocol.DuplicateMessageException) {
            Log.d(TAG, "Duplicate message, acking")
            true
        } catch (e: Exception) {
            val giveUp = envelopeRetries.recordFailure(retryKey, decrypted)
            val description = "envelope (type=${envelope.type}, sourceServiceId=${envelope.sourceServiceId}, " +
                "sourceDevice=${envelope.sourceDeviceId}, clientTs=${envelope.clientTimestamp}, " +
                "serverTs=${envelope.serverTimestamp}, urgent=${envelope.urgent}): " +
                "${e.javaClass.name}: ${e.message}"
            if (giveUp) {
                Log.e(TAG, "Giving up on $description — acking after repeated failures", e)
            } else {
                Log.w(TAG, "Failed to process $description — leaving un-acked, retrying", e)
                synchronized(heldRetries) {
                    heldRetries.add(HeldRetry(envelopeResponse, ws, System.currentTimeMillis() + HELD_ENVELOPE_RETRY_MS))
                }
            }
            giveUp
        }
        if (ack) ws.sendAck(envelopeResponse)
    }

    /**
     * The server only redelivers an un-acked envelope after a reconnect, which
     * can be hours away on a healthy connection. The read loop retries held
     * envelopes itself, between batches and on its own thread (so a retry
     * can't race other processing), through the same path: it counts towards
     * the poison limit and is acked once processed. Only envelopes received
     * on the current socket can be acked on it; the server redelivers the
     * others on the new connection, and one a redelivery already handled is
     * no longer held.
     */
    private fun retryHeldEnvelopes(cipher: SignalServiceCipher, ws: SignalWebSocket.AuthenticatedWebSocket) {
        val now = System.currentTimeMillis()
        val due = synchronized(heldRetries) {
            heldRetries.filter { it.dueAt <= now }.also { heldRetries.removeAll(it) }
        }
        for (held in due) {
            if (held.socket !== ws) continue
            if (!envelopeRetries.isHeld(envelopeRetryKey(held.response.envelope))) continue
            processEnvelope(held.response, cipher, ws)
        }
    }

    private fun processContent(content: Content, metadata: EnvelopeMetadata, envelopeResponse: EnvelopeResponse) {
        content.senderKeyDistributionMessage?.let {
            processSenderKeyDistribution(it.toByteArray(), metadata)
        }

        when {
            content.dataMessage != null -> dataMessages.processDataMessage(content.dataMessage!!, metadata)
            content.syncMessage != null -> syncMessages.processSyncMessage(content.syncMessage!!, metadata)
            content.callMessage != null -> calls.processCallMessage(content.callMessage!!, metadata, envelopeResponse)
            content.receiptMessage != null -> receipts.processReceipt(content.receiptMessage!!, metadata)
            content.typingMessage != null -> {} // ignore
            content.senderKeyDistributionMessage != null -> {} // already stored above
            else -> Log.d(TAG, "Unhandled content type")
        }
    }

    private fun processSenderKeyDistribution(serialized: ByteArray, metadata: EnvelopeMetadata) {
        if (serialized.isEmpty()) {
            Log.w(TAG, "Ignoring empty sender-key distribution from ${metadata.sourceServiceId}")
            return
        }
        try {
            val distribution = SenderKeyDistributionMessage(serialized)
            val senderAddress = SignalProtocolAddress(
                metadata.sourceServiceId.toString(),
                metadata.sourceDeviceId,
            )
            senderKeySessionBuilder.process(senderAddress, distribution)
            Log.i(
                TAG,
                "Stored sender-key distribution from ${metadata.sourceServiceId.toString().take(8)}… " +
                    "device=${metadata.sourceDeviceId} distribution=${distribution.distributionId}",
            )
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Failed to store sender-key distribution from ${metadata.sourceServiceId}: " +
                    "${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    private fun maybeSendRetryReceipt(error: ProtocolException, envelopeResponse: EnvelopeResponse) {
        if (!shouldSendRetryReceipt(error)) return

        val env = envelopeResponse.envelope
        val unidentified = error.unidentifiedSenderMessageContent.orElse(null)
        val originalContent = unidentified?.content ?: env.content?.toByteArray()
        if (originalContent == null || originalContent.isEmpty()) {
            Log.w(TAG, "Skipping retry receipt: no original ciphertext for ${error.sender}")
            return
        }

        val originalType = unidentified?.type ?: ciphertextTypeForEnvelope(env.type)
        if (originalType == null) {
            Log.w(TAG, "Skipping retry receipt: unsupported envelope type ${env.type}")
            return
        }

        val sender = error.sender.takeIf { it.isNotBlank() } ?: env.sourceServiceId
        if (sender.isNullOrBlank()) {
            Log.w(TAG, "Skipping retry receipt: missing sender")
            return
        }

        val timestamp = env.clientTimestamp ?: env.serverTimestamp ?: System.currentTimeMillis()
        val groupId = unidentified?.groupId?.orElse(null) ?: error.groupId.orElse(null)
        val sent = app.photon.service.PhotonService._signalSender?.sendRetryReceiptForDecryptionFailure(
            senderServiceId = sender,
            senderDeviceId = error.senderDevice,
            groupId = groupId,
            originalContent = originalContent,
            originalType = originalType,
            originalTimestamp = timestamp,
        ) ?: false
        if (!sent) {
            Log.w(TAG, "Retry receipt not sent for failed decrypt from ${sender.take(8)}…")
        }
    }

    private fun shouldSendRetryReceipt(error: ProtocolException): Boolean = when (error) {
        is org.signal.libsignal.metadata.ProtocolNoSessionException,
        is org.signal.libsignal.metadata.ProtocolInvalidMessageException,
        is org.signal.libsignal.metadata.ProtocolInvalidKeyIdException,
        is org.signal.libsignal.metadata.ProtocolInvalidKeyException,
        is org.signal.libsignal.metadata.ProtocolInvalidVersionException -> true
        else -> false
    }

    private fun ciphertextTypeForEnvelope(type: Envelope.Type?): Int? = when (type) {
        Envelope.Type.DOUBLE_RATCHET -> CiphertextMessage.WHISPER_TYPE
        Envelope.Type.PREKEY_MESSAGE -> CiphertextMessage.PREKEY_TYPE
        Envelope.Type.PLAINTEXT_CONTENT -> CiphertextMessage.PLAINTEXT_CONTENT_TYPE
        else -> null
    }

    /** Download an incoming attachment by message id; see [Attachments.downloadAttachment]. */
    fun downloadAttachment(messageId: String): String? = attachments.downloadAttachment(messageId)

    /** Delete downloaded media past its TTL. Called periodically by PhotonService. */
    fun pruneMedia() = attachments.pruneMedia()

    /**
     * Enforce the ephemeral message window (per-chat cap and max age from
     * Settings → Chat). Called periodically by PhotonService alongside
     * [pruneMedia]; see SignalMessageDatabase.pruneMessages for the policy.
     */
    fun pruneMessages(maxPerConversation: Int, maxAgeDays: Int) {
        val pruned = messageDb.pruneMessages(maxPerConversation, maxAgeDays)
        if (pruned > 0) {
            Log.i(TAG, "Pruned $pruned Signal messages (cap=$maxPerConversation/chat, maxAge=${maxAgeDays}d)")
        }
    }

}
