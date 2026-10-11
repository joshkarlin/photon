package app.photon.service

import android.util.Log
import app.photon.data.model.BridgeEvent
import app.photon.data.model.BridgeRequest
import app.photon.data.model.string
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Connection to the WhatsApp bridge over its process's stdin/stdout: one JSON
 * envelope per line each way (the bridge logs to stderr). Only Photon holds
 * these pipes — the localhost WebSocket this replaced was open to every app
 * on the device. Responses are matched to requests by id; everything else the
 * bridge sends is an event.
 */
class BridgeClient(private val bridge: GoBridge) {
    private val json = Json { ignoreUnknownKeys = true }

    // UI subscribers are best effort: under a burst (history sync) the oldest
    // undelivered events go, and the repositories' fallback re-query covers a
    // missed refresh. Anything that must not be missed uses subscribeLossless.
    private val _events = MutableSharedFlow<BridgeEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<BridgeEvent> = _events

    private val lossless = CopyOnWriteArrayList<Pair<Set<String>, Channel<BridgeEvent>>>()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    /** WhatsApp connection state: "connecting", "connected", "disconnected", "logged_out" */
    private val _whatsappState = MutableStateFlow("disconnected")
    val whatsappState: StateFlow<String> = _whatsappState

    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<BridgeEvent>>()

    // Serializes starting and stopping the bridge.
    private val lifecycle = Mutex()

    // The bridge being read from, if any. Only it is written to, and only its
    // lines count: a stopped bridge's reader can still be draining its last
    // output. It changes under writeLock together with isConnected, so a
    // stale reader winding down can't undo a newer attach.
    private class Attachment(val stdin: Writer)
    private val writeLock = Any()
    @Volatile private var attached: Attachment? = null

    /** Start the bridge if it isn't running and attach to it. */
    suspend fun connect() = withContext(Dispatchers.IO) {
        lifecycle.withLock { connectLocked() }
    }

    private suspend fun connectLocked() {
        if (attached != null && bridge.isRunning()) return
        // Whatever is left of the last bridge goes first: two must never
        // share the WhatsApp session.
        stopLocked()
        val process = bridge.start() ?: return
        attach(process.inputStream, process.outputStream)
    }

    /**
     * Stop the bridge and wait for it to exit (a few seconds at most);
     * nothing restarts it until [connect].
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        lifecycle.withLock { stopLocked() }
    }

    private fun stopLocked() {
        attached?.let(::detach)
        bridge.stop()
    }

    /**
     * Stop the bridge, run [block] (e.g. wipe its files), then start it again.
     * Nothing can restart the bridge in between. Runs on the IO dispatcher:
     * stopping waits for the old bridge to exit.
     */
    suspend fun whileStopped(block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            stopLocked()
            try {
                block()
            } finally {
                connectLocked()
            }
        }
    }

    /**
     * Read events and responses from [stdout] and write requests to [stdin],
     * until stdout ends (the process exited) or the bridge is stopped. One
     * bridge at a time. Internal for tests.
     */
    internal fun attach(stdout: InputStream, stdin: OutputStream) {
        val attachment = Attachment(stdin.bufferedWriter(Charsets.UTF_8))
        synchronized(writeLock) {
            check(attached == null) { "already attached to a WhatsApp bridge" }
            attached = attachment
            _isConnected.value = true
        }
        thread(isDaemon = true, name = "gobridge-stdout") {
            try {
                stdout.bufferedReader(Charsets.UTF_8).forEachLine { onLine(attachment, it) }
            } catch (_: IOException) {
                // Pipe closed under us: the process died or was stopped.
            }
            Log.w("BridgeClient", "WhatsApp bridge output ended")
            detach(attachment)
        }
    }

    /** Stop reading [from] and fail its pending requests, unless it's already gone. */
    private fun detach(from: Attachment) {
        synchronized(writeLock) {
            if (attached !== from) return
            attached = null
            _isConnected.value = false
            // The bridge reports the real state again once it's back.
            _whatsappState.value = "connecting"
            val failure = IOException("WhatsApp bridge stopped")
            pendingRequests.values.forEach { it.completeExceptionally(failure) }
            pendingRequests.clear()
        }
    }

    private fun onLine(from: Attachment, line: String) {
        if (attached !== from) return
        val event = try {
            json.decodeFromString<BridgeEvent>(line)
        } catch (e: Exception) {
            Log.w("BridgeClient", "Unparseable bridge line (${line.length} chars)", e)
            return
        }
        if (event.type == "connection_state") {
            synchronized(writeLock) {
                if (attached !== from) return
                _whatsappState.value = event.string("state") ?: "disconnected"
            }
        }
        val id = event.id
        if (id != null && pendingRequests.remove(id)?.complete(event) == true) return
        _events.tryEmit(event)
        for ((types, channel) in lossless) {
            if (event.type in types) channel.trySend(event)
        }
    }

    /**
     * Every event of [types] from now on, across bridge restarts, with none
     * dropped however slowly it's consumed. Cancelling the channel unsubscribes.
     */
    fun subscribeLossless(types: Set<String>): ReceiveChannel<BridgeEvent> {
        val channel = Channel<BridgeEvent>(Channel.UNLIMITED)
        val entry = types to channel
        lossless += entry
        channel.invokeOnClose { lossless.remove(entry) }
        return channel
    }

    suspend fun request(type: String, payload: JsonObject = buildJsonObject {}): BridgeEvent {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<BridgeEvent>()
        pendingRequests[id] = deferred
        try {
            val line = json.encodeToString(BridgeRequest(id, type, payload))
            withContext(Dispatchers.IO) { send(line) }
            return withTimeout(30_000) { deferred.await() }
        } finally {
            pendingRequests.remove(id)
        }
    }

    private fun send(line: String) {
        synchronized(writeLock) {
            val w = attached?.stdin ?: throw IllegalStateException("WhatsApp bridge not running")
            w.write(line)
            w.write("\n")
            w.flush()
        }
    }

    /** Ask the Go bridge to repair the WhatsApp socket/session connection. */
    suspend fun reconnectWhatsApp(): BridgeEvent {
        if (!_isConnected.value) {
            connect()
            withTimeout(5_000) { isConnected.first { it } }
        }
        return request("reconnect")
    }

    // Convenience methods for common requests

    suspend fun sendMessage(jid: String, text: String, replyToId: String? = null): BridgeEvent {
        return request("send_message", buildJsonObject {
            put("jid", jid)
            put("text", text)
            if (replyToId != null) put("reply_to_id", replyToId)
        })
    }

    suspend fun retryMessage(messageId: String): BridgeEvent {
        return request("retry_message", buildJsonObject {
            put("message_id", messageId)
        })
    }

    suspend fun deleteMessage(jid: String, messageId: String, forEveryone: Boolean): BridgeEvent {
        return request("delete_message", buildJsonObject {
            put("jid", jid)
            put("message_id", messageId)
            put("for_everyone", forEveryone)
        })
    }

    suspend fun resolveParticipants(jid: String): BridgeEvent {
        return request("resolve_participants", buildJsonObject {
            put("jid", jid)
        })
    }

    suspend fun sendReaction(jid: String, messageId: String, senderJid: String, emoji: String): BridgeEvent {
        return request("send_reaction", buildJsonObject {
            put("jid", jid)
            put("message_id", messageId)
            put("sender_jid", senderJid)
            put("emoji", emoji)
        })
    }

    suspend fun markRead(jid: String, messageIds: List<String>): BridgeEvent {
        return request("mark_read", buildJsonObject {
            put("jid", jid)
            put("message_ids", kotlinx.serialization.json.JsonArray(
                messageIds.map { kotlinx.serialization.json.JsonPrimitive(it) }
            ))
        })
    }

    suspend fun sendMedia(jid: String, filePath: String, mimeType: String, caption: String?, replyToId: String?): BridgeEvent {
        return request("send_media", buildJsonObject {
            put("jid", jid)
            put("file_path", filePath)
            put("mime_type", mimeType)
            if (caption != null) put("caption", caption)
            if (replyToId != null) put("reply_to_id", replyToId)
        })
    }

    suspend fun downloadMedia(messageId: String): BridgeEvent {
        return request("download_media", buildJsonObject {
            put("message_id", messageId)
        })
    }

    suspend fun requestPairingCode(phone: String): BridgeEvent {
        return request("get_pairing_code", buildJsonObject {
            put("phone", phone)
        })
    }

    suspend fun requestQr(): BridgeEvent {
        return request("get_qr")
    }
}
