package app.photon.service

import android.util.Log
import app.photon.data.model.WsEvent
import app.photon.data.model.WsRequest
import app.photon.data.model.string
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class WsClient(private val port: Int = 8765) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val _events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WsEvent> = _events

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    /** WhatsApp connection state: "connecting", "connected", "disconnected", "logged_out" */
    private val _whatsappState = MutableStateFlow("disconnected")
    val whatsappState: StateFlow<String> = _whatsappState

    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<WsEvent>>()
    private var webSocket: WebSocket? = null

    fun connect() {
        val request = Request.Builder()
            .url("ws://127.0.0.1:$port/ws")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WsClient", "Connected")
                _isConnected.value = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val event = json.decodeFromString<WsEvent>(text)
                    // Track WhatsApp connection state
                    if (event.type == "connection_state") {
                        _whatsappState.value = event.string("state") ?: "disconnected"
                    }
                    val id = event.id
                    if (id != null && pendingRequests.containsKey(id)) {
                        pendingRequests.remove(id)?.complete(event)
                    } else {
                        _events.tryEmit(event)
                    }
                } catch (e: Exception) {
                    Log.w("WsClient", "Failed to parse message: $text", e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WsClient", "Closed: $reason")
                _isConnected.value = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WsClient", "WebSocket failure", t)
                _isConnected.value = false
                pendingRequests.values.forEach { it.completeExceptionally(t) }
                pendingRequests.clear()
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "Client closing")
        webSocket = null
        _isConnected.value = false
    }

    suspend fun request(type: String, payload: JsonObject = buildJsonObject {}): WsEvent {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<WsEvent>()
        pendingRequests[id] = deferred

        val msg = json.encodeToString(WsRequest(id, type, payload))
        webSocket?.send(msg) ?: throw IllegalStateException("WebSocket not connected")

        return withTimeout(30_000) { deferred.await() }
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
