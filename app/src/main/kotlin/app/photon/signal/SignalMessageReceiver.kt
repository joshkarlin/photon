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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.signal.libsignal.metadata.certificate.CertificateValidator
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.whispersystems.signalservice.api.SignalSessionLock
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher
import org.whispersystems.signalservice.api.messages.EnvelopeResponse
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.util.SleepTimer
import org.whispersystems.signalservice.api.websocket.HealthMonitor
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.api.websocket.WebSocketFactory
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.SyncMessage
import org.whispersystems.signalservice.internal.websocket.OkHttpWebSocketConnection
import java.util.Optional
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

class SignalMessageReceiver(
    private val context: android.content.Context,
    private val credentials: SignalCredentials,
    private val protocolStore: PhotonProtocolStore,
    private val messageDb: SignalMessageDatabase,
) {
    companion object {
        private const val TAG = "SignalMessageReceiver"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val config = SignalConfig.createConfiguration()
    private val sessionLock = object : SignalSessionLock {
        private val lock = ReentrantLock()
        override fun acquire(): SignalSessionLock.Lock {
            lock.lock()
            return SignalSessionLock.Lock { lock.unlock() }
        }
    }

    private val _state = MutableStateFlow("disconnected")
    val state: StateFlow<String> = _state

    private var webSocket: SignalWebSocket.AuthenticatedWebSocket? = null
    private var unauthWebSocket: SignalWebSocket.UnauthenticatedWebSocket? = null
    private var running = false
    private var profileFetcher: SignalProfileFetcher? = null

    fun start() {
        if (running) return
        running = true
        scope.launch { receiveLoop() }
    }

    fun stop() {
        running = false
        try { webSocket?.disconnect() } catch (_: Exception) {}
        try { unauthWebSocket?.disconnect() } catch (_: Exception) {}
        webSocket = null
        unauthWebSocket = null
        profileFetcher = null
    }

    private suspend fun receiveLoop() {
        while (running) {
            try {
                _state.value = "connecting"
                // Disconnect old socket before creating new one
                try { webSocket?.disconnect() } catch (_: Exception) {}
                webSocket = null

                connectWebSocket()
                _state.value = "connected"
                Log.i(TAG, "WebSocket connected, reading messages...")
                readMessages()
            } catch (e: Exception) {
                Log.e(TAG, "Message receive error", e)
                _state.value = "disconnected"
            }
            if (running) {
                Log.i(TAG, "Reconnecting in 5s...")
                delay(5000)
            }
        }
    }

    private fun connectWebSocket() {
        val factory = WebSocketFactory {
            OkHttpWebSocketConnection(
                "photon-recv",
                config,
                Optional.of(credentials),
                SignalConfig.USER_AGENT,
                object : HealthMonitor {
                    override fun onKeepAliveResponse(sentTimestamp: Long, isIdentifiedWebSocket: Boolean) {}
                    override fun onMessageError(status: Int, isIdentifiedWebSocket: Boolean) {
                        Log.w(TAG, "Message error: $status")
                    }
                },
                true,
            )
        }

        val ws = SignalWebSocket.AuthenticatedWebSocket(
            factory,
            { running },
            object : SleepTimer { override fun sleep(millis: Long) = Thread.sleep(millis) },
            30_000L,
        )
        ws.connect()
        ws.registerKeepAliveToken("PhotonReceiver")
        webSocket = ws

        // Unauthenticated socket is used by profile fetches and key uploads.
        val unauthFactory = WebSocketFactory {
            OkHttpWebSocketConnection(
                "photon-recv-unauth", config, Optional.empty(), SignalConfig.USER_AGENT,
                object : HealthMonitor {
                    override fun onKeepAliveResponse(sentTimestamp: Long, isIdentifiedWebSocket: Boolean) {}
                    override fun onMessageError(status: Int, isIdentifiedWebSocket: Boolean) {}
                },
                false,
            )
        }
        val unauthWs = SignalWebSocket.UnauthenticatedWebSocket(
            unauthFactory, { running },
            object : SleepTimer { override fun sleep(millis: Long) = Thread.sleep(millis) },
            30_000L,
        )
        unauthWebSocket = unauthWs

        profileFetcher = SignalProfileFetcher(config, ws, unauthWs, messageDb, scope)

        // Upload one-time pre-keys if they haven't been uploaded yet
        uploadPreKeysIfNeeded(ws)
    }

    private fun uploadPreKeysIfNeeded(ws: SignalWebSocket.AuthenticatedWebSocket) {
        try {
            val unauthWs = unauthWebSocket ?: return
            val keysApi = KeysApi(ws, unauthWs)

            // Check how many pre-keys the server has
            val countResult = keysApi.getAvailablePreKeyCounts(ServiceIdType.ACI)
            val counts = countResult.successOrThrow()
            Log.i(TAG, "Server pre-key counts: ec=${counts.ecCount}, kyber=${counts.kyberCount}")

            if (counts.ecCount < 10) {
                Log.i(TAG, "Uploading one-time EC pre-keys via PushServiceSocket...")
                val preKeys = mutableListOf<PreKeyRecord>()
                val startId = (counts.ecCount + 1)
                for (i in startId..(startId + 99)) {
                    val keyPair = ECKeyPair.generate()
                    val preKey = PreKeyRecord(i, keyPair)
                    protocolStore.storePreKey(i, preKey)
                    preKeys.add(preKey)
                }

                // Also generate one-time Kyber pre-keys
                val kyberPreKeys = mutableListOf<org.signal.libsignal.protocol.state.KyberPreKeyRecord>()
                val identityKeyPair = protocolStore.identityKeyPair
                for (i in 1..100) {
                    val kyberKp = org.signal.libsignal.protocol.kem.KEMKeyPair.generate(org.signal.libsignal.protocol.kem.KEMKeyType.KYBER_1024)
                    val sig = identityKeyPair.privateKey.calculateSignature(kyberKp.publicKey.serialize())
                    val record = org.signal.libsignal.protocol.state.KyberPreKeyRecord(i + 1000, System.currentTimeMillis(), kyberKp, sig)
                    protocolStore.storeKyberPreKey(i + 1000, record)
                    kyberPreKeys.add(record)
                }

                val signedPreKey = protocolStore.loadSignedPreKeys().firstOrNull()
                val kyberPreKey = protocolStore.loadLastResortKyberPreKeys().firstOrNull()

                if (signedPreKey != null && kyberPreKey != null) {
                    // Upload via PushServiceSocket (HTTP) since KeysApi (WebSocket) returns 422
                    val pushSocket = PushServiceSocket(config, credentials, SignalConfig.USER_AGENT, false)
                    val method = pushSocket.javaClass.getDeclaredMethod("makeServiceRequest", String::class.java, String::class.java, String::class.java)
                    method.isAccessible = true
                    val preKeyState = org.whispersystems.signalservice.internal.push.PreKeyState(
                        org.whispersystems.signalservice.api.push.SignedPreKeyEntity(signedPreKey.id.toLong(), signedPreKey.keyPair.publicKey, signedPreKey.signature),
                        preKeys.map { org.whispersystems.signalservice.internal.push.PreKeyEntity(it.id.toLong(), it.keyPair.publicKey) },
                        org.whispersystems.signalservice.internal.push.KyberPreKeyEntity(kyberPreKey.id.toLong(), kyberPreKey.keyPair.publicKey, kyberPreKey.signature),
                        kyberPreKeys.map { org.whispersystems.signalservice.internal.push.KyberPreKeyEntity(it.id.toLong(), it.keyPair.publicKey, it.signature) },
                    )
                    val json = org.whispersystems.signalservice.internal.util.JsonUtil.toJson(preKeyState)
                    method.invoke(pushSocket, "/v2/keys?identity=aci", "PUT", json)
                    Log.i(TAG, "Uploaded ${preKeys.size} EC + ${kyberPreKeys.size} Kyber pre-keys")
                } else {
                    Log.w(TAG, "Missing signed or kyber pre-key for upload")
                }
            } else {
                Log.i(TAG, "Server has enough pre-keys (${counts.ecCount})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload pre-keys", e)
        }
    }

    private fun readMessages() {
        val ws = webSocket ?: return
        val aci = credentials.aci ?: return
        val localAddress = SignalServiceAddress(aci)
        val deviceId = credentials.deviceId

        // Create certificate validator for sealed sender
        val certificateValidator = try {
            val trustRootBytes = Base64.decode(SignalConfig.UNIDENTIFIED_SENDER_TRUST_ROOT, Base64.DEFAULT)
            val trustRoot = ECPublicKey(trustRootBytes)
            CertificateValidator(trustRoot)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create certificate validator, sealed sender may fail", e)
            null
        }

        val cipher = SignalServiceCipher(
            localAddress, deviceId, protocolStore, sessionLock, certificateValidator,
        )

        Log.d(TAG, "Starting message read loop")
        while (running) {
            try {
                val hasMore = ws.readMessageBatch(30_000, 10) { envelopes ->
                    Log.i(TAG, "Received batch of ${envelopes.size} envelopes")
                    for (envelope in envelopes) {
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
        }
    }

    private fun processEnvelope(
        envelopeResponse: EnvelopeResponse,
        cipher: SignalServiceCipher,
        ws: SignalWebSocket.AuthenticatedWebSocket,
    ) {
        try {
            val envelope = envelopeResponse.envelope
            val serverTimestamp = envelopeResponse.serverDeliveredTimestamp

            val result = cipher.decrypt(envelope, serverTimestamp)
            val content = result.content
            val metadata = result.metadata

            Log.i(TAG, "Decrypted envelope from ${metadata.sourceServiceId}, " +
                "hasData=${content.dataMessage != null}, " +
                "hasSync=${content.syncMessage != null}, " +
                "hasReceipt=${content.receiptMessage != null}, " +
                "hasTyping=${content.typingMessage != null}")

            when {
                content.dataMessage != null -> processDataMessage(content.dataMessage!!, metadata)
                content.syncMessage != null -> processSyncMessage(content.syncMessage!!, metadata)
                content.receiptMessage != null -> processReceipt(content.receiptMessage!!, metadata)
                content.typingMessage != null -> {} // ignore
                else -> Log.d(TAG, "Unhandled content type")
            }

            ws.sendAck(envelopeResponse)
        } catch (e: org.signal.libsignal.metadata.SelfSendException) {
            Log.d(TAG, "Self-send exception (normal for sync), acking")
            ws.sendAck(envelopeResponse)
        } catch (e: org.signal.libsignal.protocol.DuplicateMessageException) {
            Log.d(TAG, "Duplicate message, acking")
            ws.sendAck(envelopeResponse)
        } catch (e: Exception) {
            val env = envelopeResponse.envelope
            Log.e(
                TAG,
                "Failed to process envelope (type=${env.type}, sourceServiceId=${env.sourceServiceId}, " +
                    "sourceDevice=${env.sourceDeviceId}, clientTs=${env.clientTimestamp}, " +
                    "serverTs=${env.serverTimestamp}, urgent=${env.urgent}): " +
                    "${e.javaClass.name}: ${e.message}",
                e,
            )
            try { ws.sendAck(envelopeResponse) } catch (_: Exception) {}
        }
    }

    private fun processDataMessage(data: DataMessage, metadata: EnvelopeMetadata) {
        val senderAci = metadata.sourceServiceId.toString()
        val senderE164 = metadata.sourceE164
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

        // Determine conversation JID
        val groupId = metadata.groupId
        val conversationJid = if (groupId != null && groupId.isNotEmpty()) {
            Base64.encodeToString(groupId, Base64.NO_WRAP or Base64.URL_SAFE)
        } else {
            senderAci
        }
        val isGroup = groupId != null && groupId.isNotEmpty()

        // Unique message ID: use sender + timestamp + random suffix for collision resistance
        val messageId = "${senderAci}_${timestamp}_${UUID.randomUUID().toString().take(8)}"

        // Handle reactions
        if (data.reaction != null) {
            val reaction = data.reaction!!
            val emoji = reaction.emoji ?: return
            val targetTs = reaction.targetSentTimestamp ?: return
            val targetAuthor = reaction.targetAuthorAci ?: senderAci
            // Best-effort match — look for messages with matching sender+timestamp prefix
            messageDb.upsertReaction("${targetAuthor}_$targetTs", senderAci, emoji, timestamp)
            return
        }

        // Handle deletes
        if (data.delete != null) {
            val targetTs = data.delete!!.targetSentTimestamp ?: return
            messageDb.updateMessageStatus("${senderAci}_$targetTs", "deleted")
            return
        }

        // Regular message
        val body = data.body
        if (body == null && data.sticker == null) {
            // No displayable content (could be key update, profile key, etc.)
            return
        }

        val contentType = when {
            body != null -> "text"
            data.sticker != null -> "sticker"
            else -> "text"
        }

        // Ensure conversation exists
        val contactName = messageDb.getContactName(if (isGroup) conversationJid else senderAci)
            ?: senderE164
        messageDb.upsertConversation(jid = conversationJid, name = contactName, isGroup = isGroup)
        if (!isGroup) maybeFetchProfile(senderAci)

        // Quote reference (if this incoming message is a reply). Stored as the stable
        // "{authorAci}_{sentTimestampMs}" prefix — matches our local ID scheme minus the
        // random suffix, so hydration can find the original via findMessageByPrefix.
        val replyToPrefix = data.quote?.let { q ->
            val qAuthor = q.authorAci ?: return@let null
            val qId = q.id ?: return@let null
            "${qAuthor}_$qId"
        }

        messageDb.insertMessage(
            id = messageId,
            conversationJid = conversationJid,
            senderJid = senderAci,
            timestamp = timestamp / 1000,
            contentType = contentType,
            textBody = body,
            replyToId = replyToPrefix,
            isFromMe = false,
            status = "received",
        )

        messageDb.updateConversationLastMessage(conversationJid, messageId, timestamp / 1000)
        messageDb.incrementUnread(conversationJid)

        // Show notification
        app.photon.service.NotificationHelper.showMessageNotification(
            context, contactName ?: senderAci.take(8), body, "Signal", conversationJid,
        )

        Log.d(TAG, "Stored message from $senderAci: ${body?.take(30) ?: contentType}")
    }

    private fun processSyncMessage(sync: SyncMessage, metadata: EnvelopeMetadata) {
        Log.d(TAG, "Sync message: sent=${sync.sent != null}, contacts=${sync.contacts != null}, read=${!sync.read.isNullOrEmpty()}")
        // Sent messages from our primary device
        val sent = sync.sent
        if (sent != null) {
            Log.d(TAG, "Sync sent to ${sent.destinationServiceId ?: sent.destinationE164}")
        }
        if (sent != null && sent.message != null) {
            val data = sent.message!!
            // Try multiple destination fields
            val destinationAci = sent.destinationServiceId
                ?: sent.destinationServiceIdBinary?.let {
                    try { org.signal.core.models.ServiceId.parseOrNull(it.toByteArray())?.toString() }
                    catch (_: Exception) { null }
                }
                ?: sent.destinationE164
                ?: run {
                    Log.w(TAG, "Sync sent message has no destination, skipping")
                    return
                }
            val timestamp = data.timestamp ?: System.currentTimeMillis()
            val myAci = credentials.aciString ?: return
            val messageId = "${myAci}_${timestamp}_${UUID.randomUUID().toString().take(8)}"

            val body = data.body
            if (body != null) {
                // Capture destination phone and profile key so profile fetch can find names.
                sent.destinationE164?.let { messageDb.updateContactPhone(destinationAci, it) }
                val syncProfileKey = data.profileKey?.toByteArray()
                if (syncProfileKey != null && syncProfileKey.size == 32) {
                    messageDb.updateContactProfileKey(destinationAci, syncProfileKey)
                }

                // Resolve conversation name
                val convName = messageDb.getContactName(destinationAci)
                    ?: if (destinationAci == myAci) "Note to Self"
                    else sent.destinationE164

                messageDb.upsertConversation(
                    jid = destinationAci,
                    name = convName,
                    isGroup = false,
                )
                if (destinationAci != myAci) maybeFetchProfile(destinationAci)
                val syncReplyPrefix = data.quote?.let { q ->
                    val qAuthor = q.authorAci ?: return@let null
                    val qId = q.id ?: return@let null
                    "${qAuthor}_$qId"
                }
                messageDb.insertMessage(
                    id = messageId,
                    conversationJid = destinationAci,
                    senderJid = myAci,
                    timestamp = timestamp / 1000,
                    contentType = "text",
                    textBody = body,
                    replyToId = syncReplyPrefix,
                    isFromMe = true,
                    status = "sent",
                )
                messageDb.updateConversationLastMessage(destinationAci, messageId, timestamp / 1000)
            }
            Log.d(TAG, "Stored sync sent message to $destinationAci")
        }

        // Read receipts from primary — mark conversations as read
        val reads = sync.read
        if (!reads.isNullOrEmpty()) {
            for (read in reads) {
                val senderAci = read.senderAci
                if (senderAci != null) {
                    messageDb.resetUnread(senderAci)
                }
            }
        }

        // Contacts sync — store names
        val contacts = sync.contacts
        if (contacts != null) {
            Log.d(TAG, "Received contacts sync (blob — not yet processed)")
        }
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
            // Try to match messages by timestamp prefix — we added random suffixes so
            // we need to update by pattern. Use SQL LIKE for flexibility.
            messageDb.writableDatabase.execSQL(
                "UPDATE messages SET status = ? WHERE id LIKE ? AND is_from_me = 1 AND status != 'read'",
                arrayOf(newStatus, "${myAci}_${ts}_%"),
            )
        }
    }
}
