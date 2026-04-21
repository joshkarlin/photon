package app.photon.signal

import android.util.Log
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.store.PhotonProtocolStore
import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.api.SignalServiceAccountDataStore
import org.whispersystems.signalservice.api.SignalServiceDataStore
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.SignalSessionLock
import org.whispersystems.signalservice.api.attachment.AttachmentApi
import org.whispersystems.signalservice.api.crypto.ContentHint
import org.whispersystems.signalservice.api.keys.KeysApi
import org.whispersystems.signalservice.api.message.MessageApi
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.messages.multidevice.SentTranscriptMessage
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.util.SleepTimer
import org.whispersystems.signalservice.api.websocket.HealthMonitor
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.api.websocket.WebSocketFactory
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import org.whispersystems.signalservice.internal.websocket.OkHttpWebSocketConnection
import java.util.Optional
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

class SignalMessageSender(
    private val credentials: SignalCredentials,
    private val protocolStore: PhotonProtocolStore,
    private val messageDb: SignalMessageDatabase,
) {
    companion object {
        private const val TAG = "SignalMessageSender"
    }

    private val config = SignalConfig.createConfiguration()
    private val sessionLock = object : SignalSessionLock {
        private val lock = ReentrantLock()
        override fun acquire(): SignalSessionLock.Lock {
            lock.lock()
            return SignalSessionLock.Lock { lock.unlock() }
        }
    }
    private val executor = Executors.newCachedThreadPool()

    // Lazily initialized, reused across sends
    @Volatile private var sender: SignalServiceMessageSender? = null
    @Volatile private var authWs: SignalWebSocket.AuthenticatedWebSocket? = null

    private fun getOrCreateSender(): SignalServiceMessageSender {
        sender?.let { return it }

        synchronized(this) {
            sender?.let { return it }

            val pushSocket = PushServiceSocket(config, credentials, SignalConfig.USER_AGENT, false)

            val wsFactory = WebSocketFactory {
                OkHttpWebSocketConnection(
                    "photon-send", config, Optional.of(credentials), SignalConfig.USER_AGENT,
                    object : HealthMonitor {
                        override fun onKeepAliveResponse(sentTimestamp: Long, isIdentifiedWebSocket: Boolean) {}
                        override fun onMessageError(status: Int, isIdentifiedWebSocket: Boolean) {}
                    },
                    true,
                )
            }
            val unauthFactory = WebSocketFactory {
                OkHttpWebSocketConnection(
                    "photon-send-unauth", config, Optional.empty(), SignalConfig.USER_AGENT,
                    object : HealthMonitor {
                        override fun onKeepAliveResponse(sentTimestamp: Long, isIdentifiedWebSocket: Boolean) {}
                        override fun onMessageError(status: Int, isIdentifiedWebSocket: Boolean) {}
                    },
                    false,
                )
            }

            val sleepTimer = object : SleepTimer { override fun sleep(millis: Long) = Thread.sleep(millis) }
            val aws = SignalWebSocket.AuthenticatedWebSocket(wsFactory, { true }, sleepTimer, 30_000L)
            val uws = SignalWebSocket.UnauthenticatedWebSocket(unauthFactory, { true }, sleepTimer, 30_000L)
            aws.connect()
            authWs = aws

            val dataStore = object : SignalServiceDataStore {
                override fun get(serviceId: ServiceId): SignalServiceAccountDataStore = protocolStore
                override fun aci(): SignalServiceAccountDataStore = protocolStore
                override fun pni(): SignalServiceAccountDataStore = protocolStore
                override fun isMultiDevice(): Boolean = true
            }

            val s = SignalServiceMessageSender(
                pushSocket, dataStore, sessionLock,
                AttachmentApi(aws, pushSocket),
                MessageApi(aws, uws),
                KeysApi(aws, uws),
                Optional.empty(),
                executor,
                System.currentTimeMillis(),
                0,
                { true },
                false,
                false,
            )
            sender = s
            return s
        }
    }

    fun sendTextMessage(conversationJid: String, text: String, replyToId: String? = null) {
        try {
            val aci = ServiceId.ACI.parseOrNull(conversationJid)
                ?: throw IllegalArgumentException("Invalid ACI: $conversationJid")

            val timestamp = System.currentTimeMillis()
            val recipientAddress = SignalServiceAddress(aci)

            // Normalize to the stable prefix "{authorAci}_{timestampMs}". Local IDs have
            // a random suffix which we must not persist in reply_to_id — hydration looks
            // up the quoted message by prefix match.
            val replyPrefix = replyToId?.substringBeforeLast("_")
            val quote = replyPrefix?.let { buildQuote(it) }
            val builder = SignalServiceDataMessage.newBuilder()
                .withBody(text)
                .withTimestamp(timestamp)
            if (quote != null) builder.withQuote(quote)
            val message = builder.build()

            val sender = getOrCreateSender()
            sender.sendDataMessage(
                recipientAddress,
                null,   // sealed sender access
                ContentHint.RESENDABLE,
                message,
                SignalServiceMessageSender.IndividualSendEvents.EMPTY,
                false,
                false,
            )

            // Send sync transcript so other devices know we sent this
            val transcript = SentTranscriptMessage(
                Optional.of(recipientAddress),
                timestamp,
                Optional.of(message),
                message.expiresInSeconds.toLong() * 1000,
                mapOf(aci to true),
                false,
                Optional.empty(),
                emptySet(),
                Optional.empty(),
            )
            sender.sendSyncMessage(SignalServiceSyncMessage.forSentTranscript(transcript))
            Log.d(TAG, "Sent sync transcript")

            // Store outgoing message locally
            val messageId = "${credentials.aciString}_${timestamp}_${UUID.randomUUID().toString().take(8)}"
            messageDb.insertMessage(
                id = messageId,
                conversationJid = conversationJid,
                senderJid = credentials.aciString ?: "",
                timestamp = timestamp / 1000,
                contentType = "text",
                textBody = text,
                replyToId = replyPrefix,
                isFromMe = true,
                status = "sent",
            )
            messageDb.updateConversationLastMessage(conversationJid, messageId, timestamp / 1000)

            Log.i(TAG, "Sent text message to $conversationJid")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            // Invalidate sender on failure so it reconnects next time
            invalidate()
            throw e
        }
    }

    /**
     * Build a Signal quote from a local message prefix ("{authorAci}_{timestampMs}").
     * The quote transmits author + original sent timestamp; the text is a preview.
     * For media replies, attach a content-type-only QuotedAttachment (no thumbnail)
     * so the recipient renders it as a media quote rather than plain text.
     */
    private fun buildQuote(prefix: String): SignalServiceDataMessage.Quote? {
        val quoted = messageDb.findMessageByPrefix(prefix) ?: return null
        val lastUnderscore = prefix.lastIndexOf('_')
        if (lastUnderscore <= 0) return null
        val authorAciStr = prefix.substring(0, lastUnderscore)
        val timestampMs = prefix.substring(lastUnderscore + 1).toLongOrNull() ?: return null
        val author = ServiceId.parseOrNull(authorAciStr) ?: return null

        val attachments = when (quoted.contentType) {
            "image", "video", "audio", "document", "sticker" -> listOf(
                SignalServiceDataMessage.Quote.QuotedAttachment(
                    quoted.mediaMime ?: defaultMimeFor(quoted.contentType),
                    null,   // fileName (optional)
                    null,   // thumbnail — we don't re-upload the original as a thumbnail
                )
            )
            else -> emptyList()
        }

        return SignalServiceDataMessage.Quote(
            timestampMs,
            author,
            quoted.textBody ?: "",
            attachments,
            emptyList(),
            SignalServiceDataMessage.Quote.Type.NORMAL,
            emptyList(),
        )
    }

    private fun defaultMimeFor(contentType: String): String = when (contentType) {
        "image" -> "image/jpeg"
        "video" -> "video/mp4"
        "audio" -> "audio/ogg"
        "sticker" -> "image/webp"
        else -> "application/octet-stream"
    }

    fun invalidate() {
        synchronized(this) {
            try { authWs?.disconnect() } catch (_: Exception) {}
            authWs = null
            sender = null
        }
    }

    fun shutdown() {
        invalidate()
        executor.shutdown()
    }
}
