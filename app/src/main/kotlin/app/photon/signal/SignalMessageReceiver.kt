package app.photon.signal

import android.util.Base64
import android.util.Log
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.whispersystems.signalservice.api.account.PreKeyUpload
import org.whispersystems.signalservice.api.keys.KeysApi
import org.whispersystems.signalservice.api.push.ServiceIdType
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import app.photon.signal.db.SignalMessageDatabase
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
import org.signal.core.models.ServiceId
import org.signal.libsignal.metadata.ProtocolDuplicateMessageException
import org.signal.libsignal.metadata.ProtocolException
import org.signal.libsignal.metadata.certificate.CertificateValidator
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver
import org.whispersystems.signalservice.api.crypto.AttachmentCipherInputStream
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.api.crypto.SignalGroupSessionBuilder
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher
import org.whispersystems.signalservice.api.messages.EnvelopeResponse
import org.whispersystems.signalservice.api.messages.multidevice.DeviceContactsInputStream
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.util.AttachmentPointerUtil
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.CallMessage
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.Envelope
import org.whispersystems.signalservice.internal.push.SyncMessage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

class SignalMessageReceiver(
    private val context: android.content.Context,
    private val credentials: SignalCredentials,
    private val protocolStore: PhotonProtocolStore,
    private val messageDb: SignalMessageDatabase,
) {
    companion object {
        private const val TAG = "SignalMessageReceiver"
        // Generous cap on the contacts sync attachment (~10 MB is way more
        // than any plausible contact book).
        private const val MAX_CONTACTS_BLOB_BYTES: Long = 50L * 1024 * 1024
        // Cap on incoming media downloads (Signal's own attachment limit is
        // ~100 MB).
        private const val MAX_ATTACHMENT_BYTES: Long = 100L * 1024 * 1024
        // Downloaded media TTL — matches the WhatsApp bridge's ephemeral
        // policy (viewed, then auto-deleted; re-downloadable on tap while
        // the CDN pointer is still valid).
        private const val MEDIA_TTL_MS: Long = 5L * 60 * 1000
        // Conversation name used until the GroupV2 state fetch resolves the
        // real title. Also doubles as the "needs fetch" sentinel.
        private const val PLACEHOLDER_GROUP_NAME = ConversationNaming.PLACEHOLDER_GROUP_NAME
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
    @Volatile private var activeContactsPinged = false
    @Volatile private var activeContactsPingInFlight = false

    // Read syncs that arrived before their message (see EarlyReadCache).
    private val earlyReads = EarlyReadCache()
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
                contactResolver.invalidate()
                val n = messageDb.reresolveConversationNames { phone ->
                    contactResolver.resolve(phone)
                }
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
        activeContactsPinged = false
        earlyReads.clear()
        envelopeRetries.clearAll()
        synchronized(heldRetries) { heldRetries.clear() }
        context.getSharedPreferences("signal_ping_state", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
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

        // Send a NullMessage to each active Signal ACI we know about. Without
        // this, those contacts' apps don't know our linked deviceId exists and
        // won't include us in their encrypted recipient list, so their messages
        // never reach Photon. DM conversations cover direct contacts; GroupV2
        // participants cover group-only members. Once per contact (persisted
        // in SharedPreferences), with a 7-day refresh so sessions don't go stale.
        if (!activeContactsPinged) {
            activeContactsPinged = true
            scheduleActiveContactPing(8000) // let contacts sync ingest first
        }

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
        // After (re)connecting, sweep for contacts that have a profile key
        // but no resolved name — earlier fetches may have failed silently
        // (e.g. because this very WS was never connected before today). Also
        // backfill conversation names using the Android contacts provider for
        // anyone whose Signal profile fetch returns null name.
        scope.launch {
            try {
                val contacts = messageDb.getContactsWithProfileKeyButNoName()
                if (contacts.isNotEmpty()) {
                    Log.i(TAG, "Profile sweep: refetching ${contacts.size} contacts")
                    contacts.forEach { c ->
                        profileFetcher?.fetchAsync(c.jid, c.profileKey!!) { name ->
                            if (!name.isNullOrBlank()) {
                                messageDb.upsertConversation(c.jid, name, false)
                            } else {
                                // Fall back to Android contacts by phone number.
                                c.phone?.let { phone ->
                                    contactResolver.resolve(phone)?.let { androidName ->
                                        Log.i(TAG, "Backfilled ${c.jid} from Android contacts: $androidName")
                                        messageDb.upsertConversation(c.jid, androidName, false)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Profile sweep failed: ${e.message}")
            }
        }

        // Refresh conversation names from this device's local contacts.
        // Cheap: ~50 PhoneLookup queries (cached per session via the
        // resolver) plus a DB write only when the resolved name differs from
        // what's stored. A contact added in the LP3 address book is picked up
        // here; Photon's own contact editor refreshes immediately instead
        // (refreshContactNames).
        scope.launch {
            try {
                contactResolver.invalidate()
                val n = messageDb.reresolveConversationNames { phone ->
                    contactResolver.resolve(phone)
                }
                if (n > 0) Log.i(TAG, "Re-resolved $n conversation names from local contacts")
            } catch (e: Exception) {
                Log.w(TAG, "Conversation name re-resolution failed: ${e.message}")
            }
        }

        // Repair/refetch GroupV2 metadata after reconnect. Blank titles need
        // the old name-wipe repair; empty participant tables need a refetch so
        // group-only members can be session-primed below.
        scope.launch {
            try {
                val groups = messageDb.getGroupsNeedingMetadataRefresh()
                if (groups.isNotEmpty()) {
                    Log.i(TAG, "Refreshing metadata for ${groups.size} Signal group(s)")
                    groups.forEach { (jid, masterKey) ->
                        val currentName = messageDb.getConversation(jid)?.name
                        if (currentName.isNullOrBlank()) {
                            messageDb.upsertConversation(jid = jid, name = PLACEHOLDER_GROUP_NAME, isGroup = true)
                        }
                        resolveGroupMetadata(jid, masterKey)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Group metadata refresh failed: ${e.message}")
            }
        }

        // Upload one-time pre-keys if they haven't been uploaded yet
        return uploadPreKeysIfNeeded(ws, unauthWs)
    }

    private fun uploadPreKeysIfNeeded(
        ws: SignalWebSocket.AuthenticatedWebSocket,
        unauthWs: SignalWebSocket.UnauthenticatedWebSocket,
    ): Boolean {
        return try {
            val keysApi = KeysApi(ws, unauthWs)

            val countResult = keysApi.getAvailablePreKeyCounts(ServiceIdType.ACI)
            val counts = countResult.successOrThrow()
            Log.i(
                TAG,
                "Server ACI pre-key counts: ec=${counts.ecCount}, kyber=${counts.kyberCount} " +
                    "(refill threshold=10)",
            )

            if (counts.ecCount >= 10 && counts.kyberCount >= 10) {
                Log.i(TAG, "Server has enough pre-keys")
                return true
            }
            Log.w(
                TAG,
                "Server ACI pre-key counts low; uploading fresh signed, EC, and Kyber keys",
            )

            val identityKeyPair = protocolStore.identityKeyPair

            // (1) Always rotate the signed pre-key on refill. Production clients
            //     do this regularly. Re-uploading the original registration-time
            //     signed pre-key was being rejected with 422 "Invalid signature"
            //     and starving the server of one-time keys, so senders couldn't
            //     start sessions with us — the actual cause of "no incoming msgs".
            val signedPreKeyId = java.util.Random().nextInt(0xFFFFFF)
            val signedKeyPair = ECKeyPair.generate()
            val signedSignature = identityKeyPair.privateKey.calculateSignature(
                signedKeyPair.publicKey.serialize(),
            )
            val signedPreKey = org.signal.libsignal.protocol.state.SignedPreKeyRecord(
                signedPreKeyId, System.currentTimeMillis(), signedKeyPair, signedSignature,
            )
            protocolStore.storeSignedPreKey(signedPreKeyId, signedPreKey)

            // (2) Rotate the last-resort kyber pre-key too.
            val lastResortId = java.util.Random().nextInt(0xFFFFFF)
            val lastResortKp = org.signal.libsignal.protocol.kem.KEMKeyPair.generate(
                org.signal.libsignal.protocol.kem.KEMKeyType.KYBER_1024,
            )
            val lastResortSig = identityKeyPair.privateKey.calculateSignature(
                lastResortKp.publicKey.serialize(),
            )
            val lastResortKyber = org.signal.libsignal.protocol.state.KyberPreKeyRecord(
                lastResortId, System.currentTimeMillis(), lastResortKp, lastResortSig,
            )
            protocolStore.storeLastResortKyberPreKey(lastResortId, lastResortKyber)

            // (3) Fresh batches of one-time EC + Kyber pre-keys.
            val ecPreKeys = mutableListOf<PreKeyRecord>()
            val ecStartId = (counts.ecCount + 1).coerceAtLeast(1)
            for (i in ecStartId until (ecStartId + 100)) {
                val keyPair = ECKeyPair.generate()
                val rec = PreKeyRecord(i, keyPair)
                protocolStore.storePreKey(i, rec)
                ecPreKeys.add(rec)
            }
            val kyberPreKeys = mutableListOf<org.signal.libsignal.protocol.state.KyberPreKeyRecord>()
            val kyberStartId = (counts.kyberCount + 2000).coerceAtLeast(2000)
            for (i in kyberStartId until (kyberStartId + 100)) {
                val kp = org.signal.libsignal.protocol.kem.KEMKeyPair.generate(
                    org.signal.libsignal.protocol.kem.KEMKeyType.KYBER_1024,
                )
                val sig = identityKeyPair.privateKey.calculateSignature(kp.publicKey.serialize())
                val rec = org.signal.libsignal.protocol.state.KyberPreKeyRecord(
                    i, System.currentTimeMillis(), kp, sig,
                )
                protocolStore.storeKyberPreKey(i, rec)
                kyberPreKeys.add(rec)
            }

            // (4) Try the canonical websocket KeysApi first. The reflection-via-
            //     PushServiceSocket fallback is only used if that path 4xx's.
            val upload = PreKeyUpload(
                ServiceIdType.ACI, signedPreKey, ecPreKeys, lastResortKyber, kyberPreKeys,
            )
            val result = keysApi.setPreKeys(upload)
            try {
                result.successOrThrow()
                Log.i(TAG, "Uploaded fresh keys via KeysApi: signed=$signedPreKeyId " +
                    "lastResortKyber=$lastResortId ec=${ecPreKeys.size} kyber=${kyberPreKeys.size}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "KeysApi.setPreKeys failed (${e.message}); trying PushServiceSocket fallback")
            }

            // Fallback: HTTP PUT via reflection. Reuse the socket built on
            // connect when available; only the pre-connect path builds fresh.
            val push = pushSocket ?: PushServiceSocket(config, credentials, SignalConfig.USER_AGENT, false)
            val preKeyState = org.whispersystems.signalservice.internal.push.PreKeyState(
                org.whispersystems.signalservice.api.push.SignedPreKeyEntity(
                    signedPreKey.id.toLong(), signedPreKey.keyPair.publicKey, signedPreKey.signature,
                ),
                ecPreKeys.map {
                    org.whispersystems.signalservice.internal.push.PreKeyEntity(it.id.toLong(), it.keyPair.publicKey)
                },
                org.whispersystems.signalservice.internal.push.KyberPreKeyEntity(
                    lastResortKyber.id.toLong(), lastResortKyber.keyPair.publicKey, lastResortKyber.signature,
                ),
                kyberPreKeys.map {
                    org.whispersystems.signalservice.internal.push.KyberPreKeyEntity(
                        it.id.toLong(), it.keyPair.publicKey, it.signature,
                    )
                },
            )
            val json = org.whispersystems.signalservice.internal.util.JsonUtil.toJson(preKeyState)
            push.serviceRequest("/v2/keys?identity=aci", "PUT", json)
            Log.i(TAG, "Uploaded fresh keys via PushServiceSocket fallback: signed=$signedPreKeyId " +
                "lastResortKyber=$lastResortId ec=${ecPreKeys.size} kyber=${kyberPreKeys.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload pre-keys", e)
            false
        }
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

        Log.d(TAG, "Starting message read loop")
        while (isCurrent(gen)) {
            try {
                val hasMore = ws.readMessageBatch(MESSAGE_POLL_TIMEOUT_MS, 10) { envelopes ->
                    Log.i(TAG, "Received batch of ${envelopes.size} envelopes")
                    for (envelope in envelopes) {
                        val env = envelope.envelope
                        Log.i(
                            TAG,
                            "Envelope type=${env.type} src=${env.sourceServiceId ?: "sealed"} " +
                                "dest=${env.destinationServiceId} urgent=${env.urgent} " +
                                "contentLen=${env.content?.size ?: 0}",
                        )
                        processEnvelope(envelope, cipher, ws)
                    }
                }
                Log.d(TAG, "readMessageBatch returned, hasMore=$hasMore")
            } catch (e: java.util.concurrent.TimeoutException) {
                Log.d(TAG, "Read timeout (normal), looping")
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
                    processContentlessEnvelope(envelope)
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

    /**
     * Contentless envelope — the server's own delivery receipt. There's
     * nothing to decrypt; the envelope itself is the signal that our message
     * (sent at clientTimestamp) reached the recipient's server queue.
     */
    private fun processContentlessEnvelope(envelope: Envelope) {
        val myAci = credentials.aciString
        val ts = envelope.clientTimestamp
        if (envelope.type == Envelope.Type.SERVER_DELIVERY_RECEIPT && myAci != null && ts != null) {
            messageDb.updateStatusByIdPrefix("${myAci}_$ts", "delivered")
        } else {
            Log.d(TAG, "Contentless envelope (type=${envelope.type}); acking")
        }
    }

    private fun processContent(content: Content, metadata: EnvelopeMetadata, envelopeResponse: EnvelopeResponse) {
        Log.i(TAG, "Decrypted envelope from ${metadata.sourceServiceId}, " +
            "hasData=${content.dataMessage != null}, " +
            "hasSync=${content.syncMessage != null}, " +
            "hasCall=${content.callMessage != null}, " +
            "hasReceipt=${content.receiptMessage != null}, " +
            "hasTyping=${content.typingMessage != null}, " +
            "hasSenderKeyDistribution=${content.senderKeyDistributionMessage != null}")

        content.senderKeyDistributionMessage?.let {
            processSenderKeyDistribution(it.toByteArray(), metadata)
        }

        when {
            content.dataMessage != null -> processDataMessage(content.dataMessage!!, metadata)
            content.syncMessage != null -> processSyncMessage(content.syncMessage!!, metadata)
            content.callMessage != null -> processCallMessage(content.callMessage!!, metadata, envelopeResponse)
            content.receiptMessage != null -> processReceipt(content.receiptMessage!!, metadata)
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

    /**
     * Conversation rows must key Signal contacts by ACI. Some envelopes can
     * identify the sender/destination by PNI; when that happens, use the E164
     * carried with the envelope/sync transcript to find the contact-sync ACI.
     * If the mapping is not available yet, keep the raw id so the message is
     * not lost, and persist the phone mapping so a later contacts sync has
     * enough information to converge.
     */
    private fun normalizeServiceIdForConversation(
        serviceId: ServiceId,
        e164: String?,
        context: String,
    ): String {
        val raw = serviceId.toString()
        if (serviceId is ServiceId.ACI || ServiceId.ACI.parseOrNull(raw) != null) {
            return raw
        }

        val aci = e164?.let { messageDb.getAciForPhone(it) }
        if (aci != null) {
            Log.i(TAG, "$context: normalized non-ACI ${raw.take(8)}… to ACI ${aci.take(8)}… via $e164")
            return aci
        }

        if (e164 != null) {
            messageDb.updateContactPhone(raw, e164)
        }
        Log.w(
            TAG,
            "$context: non-ACI service id ${raw.take(8)}… has no ACI mapping " +
                "(phone=${e164 ?: "none"}); using raw id until contacts sync resolves it",
        )
        return raw
    }

    private fun normalizeServiceIdStringForConversation(
        serviceId: String?,
        e164: String?,
        context: String,
    ): String? {
        val parsed = serviceId?.let { ServiceId.parseOrNull(it) }
        if (parsed != null) return normalizeServiceIdForConversation(parsed, e164, context)

        val aci = e164?.let { messageDb.getAciForPhone(it) }
        if (aci != null) {
            Log.i(TAG, "$context: resolved missing/invalid service id to ACI ${aci.take(8)}… via $e164")
            return aci
        }

        if (!serviceId.isNullOrBlank()) {
            Log.w(TAG, "$context: unparseable service id ${serviceId.take(8)}…")
            return serviceId
        }
        return null
    }

    private fun processDataMessage(data: DataMessage, metadata: EnvelopeMetadata) {
        val senderE164 = metadata.sourceE164
        val senderAci = normalizeServiceIdForConversation(
            serviceId = metadata.sourceServiceId,
            e164 = senderE164,
            context = "Incoming data source",
        )
        val timestamp = data.timestamp ?: System.currentTimeMillis()

        // Phone number is the cheapest fallback name; profile fetch (below) may upgrade it.
        if (senderE164 != null) {
            messageDb.updateContactPhone(senderAci, senderE164)
        }

        // DataMessages typically include the sender's profile key — capture it so we can
        // later fetch their Signal profile (display name, avatar) via ProfileService.
        val profileKeyBytes = data.profileKey?.toByteArray()
        if (profileKeyBytes != null && profileKeyBytes.size == 32) {
            messageDb.updateContactProfileKey(senderAci, profileKeyBytes)
        }
        // Kick off an async profile fetch whenever we haven't resolved a display name yet.
        maybeFetchProfile(senderAci)

        // Determine conversation JID. Two sources of "this is a group":
        // (a) libsignal extracted `metadata.groupId` during decryption (the
        //     16-byte stable identifier), or
        // (b) the DataMessage carries `groupV2.masterKey`, from which we
        //     can derive the same identifier ourselves.
        // We prefer the masterKey path because it gives us the material
        // needed to fetch + decrypt the group's title and members.
        val groupV2 = data.groupV2
        val groupMasterKey = groupV2?.masterKey?.toByteArray()?.takeIf { it.size == 32 }
        val groupRevision = groupV2?.revision ?: 0

        val metaGroupId = metadata.groupId
        val derivedGroupId = groupMasterKey?.let { mk ->
            try { SignalGroupV2Manager.groupIdFromMasterKey(mk) } catch (_: Exception) { null }
        }

        val groupIdBytes = derivedGroupId ?: metaGroupId
        val isGroup = groupIdBytes != null && groupIdBytes.isNotEmpty()
        val conversationJid = if (isGroup) {
            Base64.encodeToString(groupIdBytes, Base64.NO_WRAP or Base64.URL_SAFE)
        } else {
            senderAci
        }

        // Stable identity of a Signal message is (author, sentTimestamp); the
        // "_{rand}" suffix only makes the local PK unique. The same message can
        // be delivered more than once — the sender resends after our retry
        // receipt / sender-key redistribution, and the server can redeliver an
        // un-acked envelope — so dedupe on the stable prefix before inserting.
        // Without this each redelivery minted a fresh random id and stored a
        // duplicate row (one group message arrived 36 times).
        val messagePrefix = "${senderAci}_${timestamp}"
        val messageId = "${messagePrefix}_${UUID.randomUUID().toString().take(8)}"

        // Seed group metadata BEFORE the body-vs-sticker early-return below.
        // Many in-group messages have no body or sticker (sender-key
        // distribution prelude, group state changes, typing indicators with
        // no payload, etc.) — but they often DO carry `groupV2.masterKey`,
        // which is the only material we need to fetch the group's name and
        // member list. Bailing out without storing it leaves the group
        // permanently unnamed and unable to receive outgoing sends.
        if (isGroup && groupMasterKey != null) {
            seedGroupConversation(conversationJid, groupMasterKey, groupRevision)
        }

        // Handle reactions (and reaction removals)
        data.reaction?.let { reaction ->
            applyReaction(reaction, senderAci, timestamp)
            return
        }

        // Handle deletes (remote delete-for-everyone). Key by the
        // "{author}_{timestampMs}" prefix — the stored id has a "_{rand}" suffix
        // the delete proto doesn't carry, so an exact-id match never matched.
        if (data.delete != null) {
            val targetTs = data.delete!!.targetSentTimestamp ?: return
            applyRemoteDelete("${senderAci}_$targetTs")
            return
        }

        // A group call started (or someone joined one). Photon can't join;
        // record it like a message so the chat shows it. Group-call update
        // messages have no body, so this must run before the empty check.
        data.groupCallUpdate?.let { update ->
            if (isGroup) {
                val name = displayNameFor(senderAci, senderE164)
                recordGroupCall(conversationJid, senderAci, name, update.eraId, timestamp)
            }
            return
        }

        // Regular message
        val body = data.body
        val attachment = data.attachments.firstOrNull()
        if (body == null && data.sticker == null && attachment == null) {
            // No displayable content (could be key update, profile key, etc.)
            return
        }

        // Media beats text: a photo with a caption is an "image" row whose
        // text_body carries the caption, mirroring the WhatsApp model.
        val contentType = when {
            attachment != null -> contentTypeForMime(attachment.contentType)
            body != null -> "text"
            else -> "sticker"
        }

        val senderName = displayNameFor(senderAci, senderE164)

        if (isGroup) {
            // Master key + conversation row already seeded above (before
            // the body check). Here we only do the per-message work:
            // record the sender as a known participant so per-message
            // sender labels render in group transcripts.
            if (!senderName.isNullOrBlank()) {
                messageDb.upsertParticipant(conversationJid, senderAci, senderName, "member")
            }
        } else {
            messageDb.upsertConversation(jid = conversationJid, name = senderName, isGroup = false)
        }

        // A read sync from another device may have beaten this message here
        // (e.g. it was resent after a decrypt failure). Then it's already
        // read: store it without counting it unread or notifying.
        val readElsewhere = earlyReads.consume(messagePrefix)

        // Redeliveries are dropped inside the insert (same author+timestamp
        // prefix), skipping every side effect below so a resent message
        // doesn't appear N times, inflate the unread count, or re-notify.
        val inserted = messageDb.insertIncomingMessage(
            id = messageId,
            conversationJid = conversationJid,
            senderJid = senderAci,
            timestamp = timestamp / 1000,
            contentType = contentType,
            textBody = body,
            mediaMime = attachment?.contentType,
            mediaSize = attachment?.size?.toLong(),
            replyToId = replyPrefix(data.quote),
            // Serialized pointer so the blob can be fetched on demand later
            // (tap-to-view); images/videos aren't auto-downloaded.
            rawProto = attachment?.encode(),
            dedupePrefix = messagePrefix,
            alreadyRead = readElsewhere,
        )
        if (!inserted) {
            Log.d(TAG, "Skipping duplicate incoming message $messagePrefix")
            return
        }

        // Voice notes auto-download (small, and the UI autoplays them on
        // open) — mirrors the WhatsApp bridge's auto-download policy.
        if (attachment != null && contentType == "audio") {
            scope.launch { downloadAttachment(messageId) }
        }

        if (readElsewhere) {
            Log.i(TAG, "Stored message $messagePrefix as read (read sync arrived first)")
            return
        }

        // Show notification — for groups, title is the group name (with the
        // sender's name woven into the body); for DMs it's the sender.
        val notifTitle = if (isGroup) {
            groupTitle(conversationJid)
        } else {
            senderName ?: senderAci.take(8)
        }
        val preview = body ?: "[$contentType]"
        val notifBody = if (isGroup && !senderName.isNullOrBlank()) {
            "$senderName: $preview"
        } else preview
        app.photon.service.NotificationHelper.showMessageNotification(
            context, notifTitle, notifBody, app.photon.data.model.Platform.SIGNAL, conversationJid,
        )

        Log.d(TAG, "Stored message from $senderAci: ${body?.take(30) ?: contentType}")
    }

    // ── Calls ──

    /**
     * 1:1 call signalling. Photon can't take part in the call (no RingRTC),
     * so it only reports it: an offer rings via a notification and records
     * an "Incoming … call" row; hangups/busy turn that into missed, answered
     * elsewhere or declined. Answer, ICE and opaque (group ring) messages
     * are WebRTC plumbing and are ignored.
     */
    private fun processCallMessage(
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
        val peerAci = normalizeServiceIdForConversation(
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
            messageDb.upsertConversation(jid = peerAci, name = displayNameFor(peerAci, peerE164), isGroup = false)
            maybeFetchProfile(peerAci)
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

        val helper = app.photon.service.NotificationHelper
        val platform = app.photon.data.model.Platform.SIGNAL
        when (outcome) {
            CallOutcome.RINGING ->
                helper.showIncomingCall(context, callerName(conversationJid), platform, conversationJid, state.isVideo)
            CallOutcome.MISSED ->
                helper.showMissedCall(context, callerName(conversationJid), platform, conversationJid, state.isVideo)
            CallOutcome.ANSWERED, CallOutcome.DECLINED -> {
                helper.cancelIncomingCall(context, conversationJid)
                // A call shown as missed turned out to be answered elsewhere.
                if (change.wasUnread && change.unreadCount == 0) {
                    helper.cancelForConversation(context, conversationJid)
                }
            }
        }
    }

    private fun callerName(conversationJid: String): String =
        messageDb.getConversation(conversationJid)?.name?.takeIf { it.isNotBlank() }
            ?: displayNameFor(conversationJid, null)
            ?: conversationJid.take(8)

    /**
     * Another device's view of a call: answered, declined or deleted there.
     * Group/ad-hoc call events are keyed by era, not callId, so they can't be
     * matched to Photon's group-call rows and are skipped.
     */
    private fun processCallEventSync(event: SyncMessage.CallEvent) {
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
        val helper = app.photon.service.NotificationHelper
        when (event.event) {
            SyncMessage.CallEvent.Event.DELETE -> {
                val change = messageDb.deleteCallRow(id) ?: return
                helper.cancelIncomingCall(context, change.conversationJid)
                if (change.wasUnread && change.unreadCount == 0) {
                    helper.cancelForConversation(context, change.conversationJid)
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
    private fun processCallLogEvent(event: SyncMessage.CallLogEvent) {
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
            app.photon.service.NotificationHelper.cancelForConversation(context, jid)
        }
    }

    /**
     * Someone started (or joined) a group call. One row per call era, however
     * many members announce it; notifies like a message, never rings.
     */
    private fun recordGroupCall(
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
        app.photon.service.NotificationHelper.showMessageNotification(
            context, groupTitle(groupJid), body, app.photon.data.model.Platform.SIGNAL, groupJid,
        )
    }

    private fun groupTitle(groupJid: String): String =
        ConversationNaming.groupTitle(messageDb.getConversation(groupJid)?.name, groupJid)

    private fun applyReaction(reaction: DataMessage.Reaction, reactorAci: String, timestamp: Long) {
        when (val update = incomingReactionFor(reaction, fallbackAuthor = reactorAci)) {
            is IncomingReaction.Set -> messageDb.upsertReaction(update.targetPrefix, reactorAci, update.emoji, timestamp)
            is IncomingReaction.Remove -> messageDb.removeReaction(update.targetPrefix, reactorAci)
            null -> Log.d(TAG, "Ignoring reaction without a target or emoji")
        }
    }

    /**
     * Blank a message deleted for everyone. If it was still unread, the
     * delete also takes it off the unread count; once nothing unread is
     * left, its notification (which may show the deleted text) goes too.
     */
    private fun applyRemoteDelete(targetPrefix: String) {
        val cleared = messageDb.markDeletedByPrefix(targetPrefix)
        cleared.forEach { jid ->
            app.photon.service.NotificationHelper.cancelForConversation(context, jid)
        }
    }

    private fun processSyncMessage(sync: SyncMessage, metadata: EnvelopeMetadata) {
        // Enumerate every non-null sub-field so we know which kind of sync this is.
        val kinds = buildList {
            if (sync.sent != null) add("sent")
            if (sync.contacts != null) add("contacts")
            if (sync.groups != null) add("groups")
            if (sync.request != null) add("request")
            if (sync.blocked != null) add("blocked")
            if (sync.verified != null) add("verified")
            if (sync.configuration != null) add("configuration")
            if (sync.viewOnceOpen != null) add("viewOnceOpen")
            sync.fetchLatest?.let { add("fetchLatest(${it.type})") }
            if (sync.keys != null) add("keys")
            if (sync.messageRequestResponse != null) add("messageRequestResponse")
            if (sync.outgoingPayment != null) add("outgoingPayment")
            if (sync.pniChangeNumber != null) add("pniChangeNumber")
            if (sync.callEvent != null) add("callEvent")
            if (sync.callLinkUpdate != null) add("callLinkUpdate")
            if (sync.callLogEvent != null) add("callLogEvent")
            if (sync.deleteForMe != null) add("deleteForMe")
            if (sync.deviceNameChange != null) add("deviceNameChange")
            if (sync.attachmentBackfillRequest != null) add("attachmentBackfillRequest")
            if (sync.attachmentBackfillResponse != null) add("attachmentBackfillResponse")
            if (!sync.read.isNullOrEmpty()) add("read[${sync.read.size}]")
            if (!sync.viewed.isNullOrEmpty()) add("viewed[${sync.viewed.size}]")
            if (!sync.stickerPackOperation.isNullOrEmpty()) add("stickerPackOperation[${sync.stickerPackOperation.size}]")
            sync.padding?.let { if (it.size > 0) add("padding[${it.size}B]") }
        }
        Log.i(TAG, "Sync message kinds: ${if (kinds.isEmpty()) "<none>" else kinds.joinToString(",")}")
        sync.verified?.let { verified ->
            try {
                val applied = applySignalVerificationSync(
                    protocolStore.identityStore, verified,
                    metadata.sourceServiceId.toString(), credentials.aciString,
                )
                Log.i(TAG, "Verification sync handled=$applied")
            } catch (e: Exception) {
                Log.w(TAG, "Invalid verification sync", e)
            }
        }
        sync.callEvent?.let { processCallEventSync(it) }
        sync.callLogEvent?.let { processCallLogEvent(it) }
        // Sent messages from our primary device
        val sent = sync.sent
        if (sent != null) {
            Log.d(TAG, "Sync sent to ${sent.destinationServiceId ?: sent.destinationE164}")
        }
        if (sent != null && sent.message != null) {
            val data = sent.message!!
            val timestamp = data.timestamp ?: System.currentTimeMillis()
            val myAci = credentials.aciString ?: return
            // Reactions and deletes-for-everyone the user made on another
            // device carry no body; apply them to the target and stop.
            data.reaction?.let { reaction ->
                applyReaction(reaction, myAci, timestamp)
                return
            }
            data.delete?.targetSentTimestamp?.let { targetTs ->
                applyRemoteDelete("${myAci}_$targetTs")
                return
            }
            // Same (author, timestamp) dedupe as incoming data messages: a sent
            // transcript can be redelivered, and the random suffix would
            // otherwise store a duplicate of our own outgoing message.
            val messagePrefix = "${myAci}_${timestamp}"
            val messageId = "${messagePrefix}_${UUID.randomUUID().toString().take(8)}"
            val alreadyStored = messageDb.findMessageByPrefix(messagePrefix) != null

            // Group case: route into the group conversation regardless of
            // destinationServiceId (which is unset for group sends from the
            // primary, and the existing handler used to drop them silently —
            // that was the root cause of groups appearing missing for users
            // who only sent into them rather than received from them).
            val groupV2 = data.groupV2
            val groupMasterKey = groupV2?.masterKey?.toByteArray()?.takeIf { it.size == 32 }
            if (groupMasterKey != null) {
                val convJid = try { SignalGroupV2Manager.jidFromMasterKey(groupMasterKey) } catch (_: Exception) { null }
                if (convJid != null) {
                    seedGroupConversation(convJid, groupMasterKey, groupV2.revision ?: 0)

                    val body = data.body
                    val attachment = data.attachments.firstOrNull()
                    if ((body != null || attachment != null) && !alreadyStored) {
                        messageDb.insertMessage(
                            id = messageId,
                            conversationJid = convJid,
                            senderJid = myAci,
                            timestamp = timestamp / 1000,
                            contentType = if (attachment != null) contentTypeForMime(attachment.contentType) else "text",
                            textBody = body,
                            mediaMime = attachment?.contentType,
                            mediaSize = attachment?.size?.toLong(),
                            replyToId = replyPrefix(data.quote),
                            isFromMe = true,
                            status = "sent",
                            rawProto = attachment?.encode(),
                        )
                        messageDb.updateConversationLastMessage(convJid, messageId, timestamp / 1000)
                        Log.d(TAG, "Stored sync sent message into group ${convJid.take(12)}…")
                    }
                    messageDb.markConversationRead(convJid)
                    app.photon.service.NotificationHelper.cancelForConversation(context, convJid)
                    return
                }
                Log.w(TAG, "Sync sent has an invalid groupV2 master key; falling through")
            }

            // DM case: needs a destination on the sync transcript. Normalize
            // PNI destinations to ACI so sent-primary transcripts land in the
            // same replyable thread Photon uses for direct sends.
            val destinationServiceIdRaw = sent.destinationServiceId
            val destinationServiceId = destinationServiceIdRaw?.let { ServiceId.parseOrNull(it) }
                ?: sent.destinationServiceIdBinary?.let {
                    try { ServiceId.parseOrNull(it.toByteArray()) }
                    catch (_: Exception) { null }
                }
            val destinationAci = destinationServiceId?.let {
                normalizeServiceIdForConversation(it, sent.destinationE164, "Sync sent destination")
            }
                ?: normalizeServiceIdStringForConversation(
                    destinationServiceIdRaw,
                    sent.destinationE164,
                    "Sync sent destination",
                )
                ?: sent.destinationE164
                ?: run {
                    Log.w(TAG, "Sync sent message has no destination, skipping")
                    return
                }

            val body = data.body
            val attachment = data.attachments.firstOrNull()
            if ((body != null || attachment != null) && !alreadyStored) {
                // Capture destination phone and profile key so profile fetch can find names.
                sent.destinationE164?.let { messageDb.updateContactPhone(destinationAci, it) }
                val syncProfileKey = data.profileKey?.toByteArray()
                if (syncProfileKey != null && syncProfileKey.size == 32) {
                    messageDb.updateContactProfileKey(destinationAci, syncProfileKey)
                }

                // Resolve conversation name. Order: Android local contacts
                // → Signal profile name → phone number. Do NOT use primary's
                // address-book name (we ingest only phone numbers from
                // SyncMessage.contacts, not names).
                val phone = sent.destinationE164
                val storedProfileName = messageDb.getContact(destinationAci)
                    ?.profileName?.takeIf { it.isNotBlank() }
                val convName = when {
                    destinationAci == myAci -> "Note to Self"
                    phone != null -> contactResolver.resolve(phone) ?: storedProfileName ?: phone
                    else -> storedProfileName
                }

                messageDb.upsertConversation(
                    jid = destinationAci,
                    name = convName,
                    isGroup = false,
                )
                if (destinationAci != myAci) maybeFetchProfile(destinationAci)
                messageDb.insertMessage(
                    id = messageId,
                    conversationJid = destinationAci,
                    senderJid = myAci,
                    timestamp = timestamp / 1000,
                    contentType = if (attachment != null) contentTypeForMime(attachment.contentType) else "text",
                    textBody = body,
                    mediaMime = attachment?.contentType,
                    mediaSize = attachment?.size?.toLong(),
                    replyToId = replyPrefix(data.quote),
                    isFromMe = true,
                    status = "sent",
                    rawProto = attachment?.encode(),
                )
                messageDb.updateConversationLastMessage(destinationAci, messageId, timestamp / 1000)
            }
            // A sent transcript means the user has seen this thread on the
            // primary. Some primary/device combinations send sync.sent without
            // the corresponding sync.read, so clear any stale local incoming
            // unread state as a conservative fallback.
            messageDb.markConversationRead(destinationAci)
            app.photon.service.NotificationHelper.cancelForConversation(context, destinationAci)
            Log.d(TAG, "Stored sync sent message to $destinationAci")
        }

        // Read receipts from primary — mark the matching messages as read AND
        // clear their local notifications so the banner disappears as soon as
        // the user reads elsewhere, not only when they open the chat in Photon.
        val reads = sync.read
        if (!reads.isNullOrEmpty()) {
            for (read in reads) {
                val senderAci = serviceIdString(read.senderAci, read.senderAciBinary) ?: continue
                val timestamp = read.timestamp ?: continue
                val prefix = "${senderAci}_$timestamp"
                val result = messageDb.markIncomingReadByPrefix(prefix)
                // The read beat its message here; remember it so the message
                // lands already read instead of unread with a notification.
                if (!result.matched) earlyReads.remember(prefix)
                // Only conversations this read actually emptied: newer unread
                // messages keep their notification.
                result.clearedConversations.forEach { conversationJid ->
                    app.photon.service.NotificationHelper.cancelForConversation(context, conversationJid)
                }
            }
        }

        // Contacts sync — download the attachment blob and ingest each
        // DeviceContact (name + phone + ACI from the primary's address book).
        val contacts = sync.contacts
        if (contacts != null) {
            val blob = contacts.blob
            if (blob != null) {
                scope.launch { processContactsBlob(blob) }
            } else {
                Log.d(TAG, "Contacts sync had no blob")
            }
        }
    }

    /**
     * Map an attachment mime type to our content_type column vocabulary.
     */
    private fun contentTypeForMime(mime: String?): String = when {
        mime == null -> "document"
        mime.startsWith("image/") -> "image"
        mime.startsWith("video/") -> "video"
        mime.startsWith("audio/") -> "audio"
        else -> "document"
    }

    private fun extensionForMime(mime: String?): String = when {
        mime == null -> ""
        mime.startsWith("image/png") -> ".png"
        mime.startsWith("image/webp") -> ".webp"
        mime.startsWith("image/gif") -> ".gif"
        mime.startsWith("image/") -> ".jpg"
        mime.startsWith("video/") -> ".mp4"
        mime.startsWith("audio/") -> ".ogg"
        else -> ""
    }

    /**
     * Download an incoming attachment by message id. The serialized
     * AttachmentPointer was stored in raw_proto at receive time; this fetches
     * and decrypts the blob from Signal's CDN, writes it under
     * filesDir/signal_media, and records the path on the message row so the
     * UI renders it directly next time. Returns the local path, or null when
     * the pointer is missing/expired (Signal CDN attachments expire after
     * ~45 days) or the download fails. Safe to call repeatedly — an existing
     * downloaded file short-circuits.
     */
    fun downloadAttachment(messageId: String): String? {
        val push = pushSocket ?: run {
            Log.w(TAG, "downloadAttachment: pushSocket not initialised")
            return null
        }
        val row = messageDb.getMessage(messageId) ?: return null
        row.mediaUrl?.let { existing ->
            if (File(existing).exists()) return existing
        }
        val pointerBytes = messageDb.getMessageRawProto(messageId) ?: run {
            Log.w(TAG, "downloadAttachment: no stored pointer for $messageId")
            return null
        }
        return try {
            val proto = AttachmentPointer.ADAPTER.decode(pointerBytes)
            val pointer = AttachmentPointerUtil.createSignalAttachmentPointer(proto)
            val digest = pointer.digest.orElse(null) ?: run {
                Log.w(TAG, "downloadAttachment: pointer has no digest for $messageId")
                return null
            }
            val mediaDir = File(context.filesDir, "signal_media").apply { mkdirs() }
            val cipherFile = File(context.cacheDir, "sig_att_$messageId.bin")
            try {
                val messageReceiver = SignalServiceMessageReceiver(push)
                val plain = messageReceiver.retrieveAttachment(
                    pointer,
                    cipherFile,
                    MAX_ATTACHMENT_BYTES,
                    AttachmentCipherInputStream.IntegrityCheck.forEncryptedDigest(digest),
                )
                val outFile = File(mediaDir, messageId + extensionForMime(row.mediaMime))
                plain.use { input -> outFile.outputStream().use { input.copyTo(it) } }
                messageDb.updateMediaUrl(messageId, outFile.absolutePath)
                Log.i(TAG, "Downloaded attachment for $messageId (${outFile.length()}B)")
                outFile.absolutePath
            } finally {
                cipherFile.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Attachment download failed for $messageId: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /**
     * Delete downloaded Signal media older than the TTL and clear the
     * media_url on the affected rows (the stored AttachmentPointer keeps
     * tap-to-redownload working). Called periodically by PhotonService.
     */
    fun pruneMedia() {
        val mediaDir = File(context.filesDir, "signal_media")
        val cutoff = System.currentTimeMillis() - MEDIA_TTL_MS
        var pruned = 0
        mediaDir.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff && f.delete()) {
                messageDb.clearMediaUrlByPath(f.absolutePath)
                pruned++
            }
        }
        if (pruned > 0) Log.i(TAG, "Pruned $pruned downloaded Signal media files")
    }

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

    /**
     * Downloads, decrypts, and parses a sync.contacts attachment.
     * Each DeviceContact in the stream gets its name + phone stored as a
     * contact record, and any conversation with that ACI gets its display
     * name updated to the primary device's local contact name.
     */
    private fun processContactsBlob(blob: AttachmentPointer) {
        val push = pushSocket
        if (push == null) {
            Log.w(TAG, "processContactsBlob: pushSocket not initialised")
            return
        }
        val publicPointer = try {
            AttachmentPointerUtil.createSignalAttachmentPointer(blob)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build attachment pointer: ${e.message}")
            return
        }
        val digest = publicPointer.digest.orElse(null)
        if (digest == null) {
            Log.w(TAG, "Contacts blob has no digest, can't verify; skipping")
            return
        }
        val tempDir = File(context.cacheDir, "signal_sync").apply { mkdirs() }
        val cipherFile = File(tempDir, "contacts_${System.currentTimeMillis()}.bin")
        try {
            val messageReceiver = SignalServiceMessageReceiver(push)
            val plain = messageReceiver.retrieveAttachment(
                publicPointer,
                cipherFile,
                MAX_CONTACTS_BLOB_BYTES,
                AttachmentCipherInputStream.IntegrityCheck.forEncryptedDigest(digest),
            )
            val stream = DeviceContactsInputStream(plain)
            var count = 0
            while (true) {
                // Catch Throwable (not just Exception) — a malformed/truncated
                // stream can produce nonsense length prefixes that trigger
                // OutOfMemoryError when the parser tries to allocate a buffer.
                // OOM is Error not Exception, so a plain Exception catch lets
                // it escape and crashes the process.
                val contact = try {
                    stream.read()
                } catch (t: Throwable) {
                    Log.w(TAG, "DeviceContactsInputStream.read threw after $count contacts: ${t.javaClass.simpleName}: ${t.message}")
                    break
                } ?: break
                try {
                    ingestDeviceContact(contact)
                    count++
                } catch (t: Throwable) {
                    Log.w(TAG, "ingestDeviceContact threw: ${t.message}")
                }
                // DeviceContactsInputStream returns each contact with a
                // LimitedInputStream wrapping its avatar bytes. If we don't
                // drain it, the underlying stream is still pointing inside
                // the avatar payload when read() is called for the next
                // contact — and the parser will interpret avatar bytes as a
                // varint length, leading to OOM on the bogus allocation.
                contact.avatar.orElse(null)?.inputStream?.let { avatarStream ->
                    try {
                        val buf = ByteArray(8192)
                        while (avatarStream.read(buf) >= 0) { /* discard */ }
                    } catch (_: Throwable) { /* ignore */ }
                }
            }
            Log.i(TAG, "Contacts sync: ingested $count contacts")
        } catch (t: Throwable) {
            Log.w(TAG, "Contacts sync download/parse failed: ${t.javaClass.simpleName}: ${t.message}", t)
        } finally {
            cipherFile.delete()
        }
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
        val sender = app.photon.service.PhotonService._signalSender ?: run {
            Log.d(TAG, "Skipping active-contact ping: sender not ready")
            return
        }
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
            val ok = sender.sendSessionPing(jid)
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

    private fun scheduleActiveContactPing(delayMs: Long = 0L) {
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
                synchronized(this@SignalMessageReceiver) {
                    activeContactsPingInFlight = false
                }
            }
        }
    }

    private fun ingestDeviceContact(
        contact: org.whispersystems.signalservice.api.messages.multidevice.DeviceContact,
    ) {
        val aci = contact.aci.orElse(null)?.toString() ?: return
        val phone = contact.e164.orElse(null)

        // We intentionally do NOT ingest the name from the primary's address
        // book. The user wants this device's local contacts to be the source
        // of truth — names get resolved via AndroidContactResolver at the
        // point of writing the conversation row. We only persist the
        // ACI<->phone mapping here so that resolution can happen on
        // incoming messages even before the contact has messaged us.
        if (phone != null) {
            messageDb.updateContactPhone(aci, phone)
            // Pre-resolve a name from local contacts and upsert the
            // conversation row. If there's no local match the conversation
            // stays nameless (UI will show the phone number).
            val androidName = contactResolver.resolve(phone)
            if (androidName != null) {
                messageDb.upsertConversation(jid = aci, name = androidName, isGroup = false)
                Log.d(TAG, "Synced contact $aci: phone=$phone, local name=$androidName")
            } else {
                Log.d(TAG, "Synced contact $aci: phone=$phone (no local name)")
            }
        }
    }

    /**
     * Quote references are stored as the stable "{authorAci}_{sentTimestampMs}"
     * prefix — matches our local ID scheme minus the random suffix, so
     * hydration can find the original via findMessageByPrefix.
     */
    private fun replyPrefix(quote: DataMessage.Quote?): String? {
        val author = serviceIdString(quote?.authorAci, quote?.authorAciBinary) ?: return null
        val id = quote?.id ?: return null
        return "${author}_$id"
    }

    /**
     * Seed (or refresh) a GroupV2 conversation row from a message that
     * carries the group's master key. Plants a placeholder name on first
     * sight, persists masterKey + revision, and kicks off an async state
     * fetch whenever the name is still unresolved or the revision moved.
     * Shared by the incoming-data and sync-sent paths.
     */
    private fun seedGroupConversation(conversationJid: String, masterKey: ByteArray, revision: Int) {
        val existingName = messageDb.getConversation(conversationJid)?.name
        val placeholder = existingName?.takeIf { it.isNotBlank() && it != conversationJid }
            ?: PLACEHOLDER_GROUP_NAME
        messageDb.upsertConversation(jid = conversationJid, name = placeholder, isGroup = true)
        val storedRevision = messageDb.getGroupMeta(conversationJid)?.revision ?: -1
        messageDb.updateGroupMeta(conversationJid, masterKey, revision)
        val needsFetch = existingName.isNullOrBlank() ||
            existingName == PLACEHOLDER_GROUP_NAME ||
            existingName == conversationJid ||
            storedRevision < revision
        if (needsFetch) {
            Log.i(TAG, "Group ${conversationJid.take(12)}…: seeding masterKey + fetching state (rev=$revision)")
            scope.launch { resolveGroupMetadata(conversationJid, masterKey) }
        }
    }

    /**
     * Async-fetch the GroupV2 state from the server (title + members),
     * decrypt it via libsignal's GroupsV2Operations, and write back: the
     * conversation row's `name` becomes the decrypted title, and every
     * member ACI lands in the participants table so per-message sender
     * names show up in the chat. Failures are non-fatal — the placeholder
     * "Signal group" stays until the next message triggers another try.
     */
    private fun resolveGroupMetadata(conversationJid: String, masterKey: ByteArray) {
        val gm = groupManager ?: run {
            Log.w(TAG, "Group fetch skipped: manager not ready for ${conversationJid.take(12)}…")
            return
        }
        val group = gm.fetchGroup(masterKey) ?: run {
            Log.w(TAG, "Group fetch returned null for ${conversationJid.take(12)}… — see GroupV2Manager warnings")
            return
        }
        val title = group.title?.takeIf { it.isNotBlank() } ?: PLACEHOLDER_GROUP_NAME
        messageDb.upsertConversation(jid = conversationJid, name = title, isGroup = true)

        val acis = gm.memberAcis(group)
        for (memberAci in acis) {
            val aciString = memberAci.toString()
            val name = messageDb.memberDisplayName(aciString) { contactResolver.resolve(it) }
            messageDb.upsertParticipant(conversationJid, aciString, name, "member")
        }
        Log.i(TAG, "Resolved group ${conversationJid.take(12)}…: $title (${acis.size} members)")
        scheduleActiveContactPing()
    }

    /**
     * Friendly name for a Signal contact. Order: Android local contacts (this
     * device's address book) → Signal profile name (when it actually returns
     * one) → bare E.164. The envelope's E.164 is usually absent under sealed
     * sender, so fall back to the phone learned from contacts sync. Names from
     * the primary device's address book are deliberately never used.
     */
    private fun displayNameFor(aci: String, e164: String?): String? {
        val contact = messageDb.getContact(aci)
        return ConversationNaming.resolveFromContact(
            phone = e164 ?: contact?.phone,
            profileName = contact?.profileName,
        ) { contactResolver.resolve(it) }
    }

    private fun maybeFetchProfile(aci: String) {
        val fetcher = profileFetcher ?: return
        val contact = messageDb.getContact(aci) ?: return
        if (contact.profileKey == null) return
        // Re-fetch only if we don't yet have a profile name or it's older than a day.
        val stale = System.currentTimeMillis() - contact.profileFetchedAt > 24 * 60 * 60 * 1000
        if (contact.profileName.isNullOrBlank() || stale) {
            fetcher.fetchAsync(aci, contact.profileKey) { resolvedName ->
                if (!resolvedName.isNullOrBlank()) {
                    messageDb.upsertConversation(
                        jid = aci,
                        name = resolvedName,
                        isGroup = false,
                    )
                }
            }
        }
    }

    private fun processReceipt(
        receipt: org.whispersystems.signalservice.internal.push.ReceiptMessage,
        metadata: EnvelopeMetadata,
    ) {
        val timestamps = receipt.timestamp
        val type = receipt.type
        val myAci = credentials.aciString ?: return

        val newStatus = when (type) {
            org.whispersystems.signalservice.internal.push.ReceiptMessage.Type.READ -> "read"
            org.whispersystems.signalservice.internal.push.ReceiptMessage.Type.DELIVERY -> "delivered"
            else -> return
        }

        for (ts in timestamps) {
            // Receipts only carry the original sent timestamp; match our rows
            // by the stable "{aci}_{ts}" prefix (ids carry a random suffix).
            messageDb.updateStatusByIdPrefix("${myAci}_$ts", newStatus)
        }
    }
}
