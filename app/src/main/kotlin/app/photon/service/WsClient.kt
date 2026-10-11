package app.photon.service

import android.util.Log
import app.photon.data.model.WsEvent
import app.photon.data.model.WsRequest
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
 * bridge sends is an event. The name is historical; the API is unchanged.
 */
class WsClient(private val bridge: GoBridge) {
    private val json = Json { ignoreUnknownKeys = true }

    // UI subscribers are best effort: under a burst (history sync) the oldest
    // undelivered events go, and the repositories' fallback re-query covers a
    // missed refresh. Anything that must not be missed uses subscribeLossless.
    private val _events = MutableSharedFlow<WsEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<WsEvent> = _events

    private val lossless = CopyOnWriteArrayList<Pair<Set<String>, Channel<WsEvent>>>()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    /** WhatsApp connection state: "connecting", "connected", "disconnected", "logged_out" */
    private val _whatsappState = MutableStateFlow("disconnected")
    val whatsappState: StateFlow<String> = _whatsappState

    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<WsEvent>>()

    // Serializes starting and stopping the bridge.
    private val lifecycle = Mutex()

    // Guards stdin and generation. generation counts attached processes so a
    // dead process's reader can't detach its successor.
    private val writeLock = Any()
    private var stdin: Writer? = null
    private var generation = 0

    /** Start the bridge if it isn't running and attach to it. */
    suspend fun connect() {
        lifecycle.withLock { connectLocked() }
    }

    private suspend fun connectLocked() {
        if (_isConnected.value && bridge.isRunning()) return
        val process = bridge.start() ?: return
        attach(process.inputStream, process.outputStream)
    }

    /** Stop the bridge; nothing restarts it until [connect]. */
    fun disconnect() {
        detach(currentGeneration())
        bridge.stop()
    }

    /**
     * Stop the bridge, run [block] (e.g. wipe its files), then start it again.
     * Nothing can restart the bridge in between.
     */
    suspend fun whileStopped(block: suspend () -> Unit) {
        lifecycle.withLock {
            disconnect()
            try {
                block()
            } finally {
                connectLocked()
            }
        }
    }

    /**
     * Read events and responses from [stdout] and write requests to [stdin],
     * until stdout ends (the process exited). Internal for tests.
     */
    internal fun attach(stdout: InputStream, stdin: OutputStream) {
        val gen = synchronized(writeLock) {
            this.stdin = stdin.bufferedWriter(Charsets.UTF_8)
            ++generation
        }
        _isConnected.value = true
        thread(isDaemon = true, name = "gobridge-stdout") {
            try {
                stdout.bufferedReader(Charsets.UTF_8).forEachLine(::onLine)
            } catch (_: IOException) {
                // Pipe closed under us: the process died or was stopped.
            }
            Log.w("WsClient", "WhatsApp bridge output ended")
            detach(gen)
        }
    }

    private fun currentGeneration(): Int = synchronized(writeLock) { generation }

    private fun detach(gen: Int) {
        synchronized(writeLock) {
            if (gen != generation || stdin == null) return
            stdin = null
        }
        _isConnected.value = false
        // The bridge reports the real state again once it's back.
        _whatsappState.value = "connecting"
        val failure = IOException("WhatsApp bridge stopped")
        pendingRequests.values.forEach { it.completeExceptionally(failure) }
        pendingRequests.clear()
    }

    private fun onLine(line: String) {
        val event = try {
            json.decodeFromString<WsEvent>(line)
        } catch (e: Exception) {
            Log.w("WsClient", "Unparseable bridge line (${line.length} chars)", e)
            return
        }
        if (event.type == "connection_state") {
            _whatsappState.value = event.string("state") ?: "disconnected"
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
    fun subscribeLossless(types: Set<String>): ReceiveChannel<WsEvent> {
        val channel = Channel<WsEvent>(Channel.UNLIMITED)
        val entry = types to channel
        lossless += entry
        channel.invokeOnClose { lossless.remove(entry) }
        return channel
    }

    suspend fun request(type: String, payload: JsonObject = buildJsonObject {}): WsEvent {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<WsEvent>()
        pendingRequests[id] = deferred
        try {
            val line = json.encodeToString(WsRequest(id, type, payload))
            withContext(Dispatchers.IO) { send(line) }
            return withTimeout(30_000) { deferred.await() }
        } finally {
            pendingRequests.remove(id)
        }
    }

    private fun send(line: String) {
        synchronized(writeLock) {
            val w = stdin ?: throw IllegalStateException("WhatsApp bridge not running")
            w.write(line)
            w.write("\n")
            w.flush()
        }
    }

    /** Ask the Go bridge to repair the WhatsApp socket/session connection. */
    suspend fun reconnectWhatsApp(): WsEvent {
        if (!_isConnected.value) {
            connect()
            withTimeout(5_000) { isConnected.first { it } }
        }
        return request("reconnect")
    }

    // Convenience methods for common requests

    suspend fun sendMessage(jid: String, text: String, replyToId: String? = null): WsEvent {
        return request("send_message", buildJsonObject {
            put("jid", jid)
            put("text", text)
            if (replyToId != null) put("reply_to_id", replyToId)
        })
    }

    suspend fun retryMessage(messageId: String): WsEvent {
        return request("retry_message", buildJsonObject {
            put("message_id", messageId)
        })
    }

    suspend fun deleteMessage(jid: String, messageId: String, forEveryone: Boolean): WsEvent {
        return request("delete_message", buildJsonObject {
            put("jid", jid)
            put("message_id", messageId)
            put("for_everyone", forEveryone)
        })
    }

    suspend fun resolveParticipants(jid: String): WsEvent {
        return request("resolve_participants", buildJsonObject {
            put("jid", jid)
        })
    }

    suspend fun sendReaction(jid: String, messageId: String, senderJid: String, emoji: String): WsEvent {
        return request("send_reaction", buildJsonObject {
            put("jid", jid)
            put("message_id", messageId)
            put("sender_jid", senderJid)
            put("emoji", emoji)
        })
    }

    suspend fun markRead(jid: String, messageIds: List<String>): WsEvent {
        return request("mark_read", buildJsonObject {
            put("jid", jid)
            put("message_ids", kotlinx.serialization.json.JsonArray(
                messageIds.map { kotlinx.serialization.json.JsonPrimitive(it) }
            ))
        })
    }

    suspend fun sendMedia(jid: String, filePath: String, mimeType: String, caption: String?, replyToId: String?): WsEvent {
        return request("send_media", buildJsonObject {
            put("jid", jid)
            put("file_path", filePath)
            put("mime_type", mimeType)
            if (caption != null) put("caption", caption)
            if (replyToId != null) put("reply_to_id", replyToId)
        })
    }

    suspend fun downloadMedia(messageId: String): WsEvent {
        return request("download_media", buildJsonObject {
            put("message_id", messageId)
        })
    }

    suspend fun requestPairingCode(phone: String): WsEvent {
        return request("get_pairing_code", buildJsonObject {
            put("phone", phone)
        })
    }

    suspend fun requestQr(): WsEvent {
        return request("get_qr")
    }
}
