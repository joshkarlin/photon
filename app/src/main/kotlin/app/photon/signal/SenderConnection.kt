package app.photon.signal

import android.util.Log
import app.photon.signal.store.PhotonProtocolStore
import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.api.SignalServiceAccountDataStore
import org.whispersystems.signalservice.api.SignalServiceDataStore
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.attachment.AttachmentApi
import org.whispersystems.signalservice.api.keys.KeysApi
import org.whispersystems.signalservice.api.message.MessageApi
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import java.util.Optional
import java.util.concurrent.Executors

// Logged under the sender's tag, which existing logcat filters use.
private const val TAG = "SignalMessageSender"

/** The library message sender behind [SignalMessageSender], and the sockets it sends over. */
internal class SenderConnection(
    private val credentials: SignalCredentials,
    private val protocolStore: PhotonProtocolStore,
    private val config: () -> SignalServiceConfiguration,
) {
    private val sessionLock = SignalConfig.newSessionLock()
    private val executor = Executors.newCachedThreadPool()

    // Lazily initialized, reused across sends
    @Volatile private var sender: SignalServiceMessageSender? = null
    // Signal permits one authenticated chat WebSocket per linked device. The
    // receiver owns the long-lived sockets when it is running; creating a
    // second authenticated socket here causes the receiver socket to be
    // closed by the service, which looks like a receive failure.
    @Volatile private var receiverAuthWs: SignalWebSocket.AuthenticatedWebSocket? = null
    @Volatile private var receiverUnauthWs: SignalWebSocket.UnauthenticatedWebSocket? = null
    private var ownedAuthWs: SignalWebSocket.AuthenticatedWebSocket? = null
    private var ownedUnauthWs: SignalWebSocket.UnauthenticatedWebSocket? = null

    /**
     * Reuse the receiver's sockets for sends, sync requests, and key APIs.
     * This keeps one authenticated connection per linked device. The receiver
     * remains responsible for connecting and disconnecting these instances.
     */
    fun attachReceiverWebSockets(
        authWs: SignalWebSocket.AuthenticatedWebSocket,
        unauthWs: SignalWebSocket.UnauthenticatedWebSocket,
    ) {
        var oldOwnedAuth: SignalWebSocket.AuthenticatedWebSocket? = null
        var oldOwnedUnauth: SignalWebSocket.UnauthenticatedWebSocket? = null
        synchronized(this) {
            if (receiverAuthWs === authWs && receiverUnauthWs === unauthWs) return
            oldOwnedAuth = ownedAuthWs
            oldOwnedUnauth = ownedUnauthWs
            ownedAuthWs = null
            ownedUnauthWs = null
            sender = null
            receiverAuthWs = authWs
            receiverUnauthWs = unauthWs
        }
        // These are only sockets created by this sender. Never disconnect the
        // receiver-owned sockets during an attachment or an invalidation.
        try { oldOwnedAuth?.disconnect() } catch (_: Exception) {}
        try { oldOwnedUnauth?.disconnect() } catch (_: Exception) {}
        Log.i(TAG, "Attached sender to receiver-owned Signal WebSockets")
    }

    /** Clear only the receiver socket pair being stopped or replaced. */
    fun detachReceiverWebSockets(
        authWs: SignalWebSocket.AuthenticatedWebSocket?,
        unauthWs: SignalWebSocket.UnauthenticatedWebSocket?,
    ) {
        if (authWs == null && unauthWs == null) return
        synchronized(this) {
            if (receiverAuthWs === authWs || receiverUnauthWs === unauthWs) {
                receiverAuthWs = null
                receiverUnauthWs = null
                sender = null
            }
        }
    }

    fun getOrCreateSender(): SignalServiceMessageSender {
        sender?.let { return it }

        synchronized(this) {
            sender?.let { return it }

            val pushSocket = PushServiceSocket(config(), credentials, SignalConfig.USER_AGENT, false)

            val aws = receiverAuthWs ?: run {
                val wsFactory = SignalConfig.webSocketFactory(config(), "photon-send", credentials)
                SignalWebSocket.AuthenticatedWebSocket(
                    wsFactory, { true }, SignalConfig.sleepTimer, 30_000L,
                ).also {
                    it.connect()
                    ownedAuthWs = it
                }
            }
            val uws = receiverUnauthWs ?: run {
                val unauthFactory = SignalConfig.webSocketFactory(config(), "photon-send-unauth", null)
                SignalWebSocket.UnauthenticatedWebSocket(
                    unauthFactory, { true }, SignalConfig.sleepTimer, 30_000L,
                ).also { ownedUnauthWs = it }
            }

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
                0L,    // maxEnvelopeSize (0 = unenforced)
                0,     // maxIncrementalMacsPerEnvelope
                { true },
                // useBinaryId / useStringId: these gate whether destination
                // ServiceIds are written into sync payloads AT ALL. With both
                // false (the old values), sent transcripts carried no
                // destinationServiceId in any form, so the primary device
                // couldn't attribute them to a thread and silently dropped
                // them — outgoing messages never appeared on the main device.
                // Current clients read the binary field; the string field is
                // included for older-client compatibility.
                true,
                true,
            )
            sender = s
            return s
        }
    }

    fun invalidate() {
        var oldOwnedAuth: SignalWebSocket.AuthenticatedWebSocket? = null
        var oldOwnedUnauth: SignalWebSocket.UnauthenticatedWebSocket? = null
        synchronized(this) {
            oldOwnedAuth = ownedAuthWs
            oldOwnedUnauth = ownedUnauthWs
            ownedAuthWs = null
            ownedUnauthWs = null
            sender = null
        }
        try { oldOwnedAuth?.disconnect() } catch (_: Exception) {}
        try { oldOwnedUnauth?.disconnect() } catch (_: Exception) {}
    }

    fun shutdown() {
        invalidate()
        executor.shutdown()
    }
}
