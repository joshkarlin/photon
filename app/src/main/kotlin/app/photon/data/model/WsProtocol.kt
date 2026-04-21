package app.photon.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class WsRequest(
    val id: String,
    val type: String,
    val payload: JsonObject = buildJsonObject {},
)

@Serializable
data class WsEvent(
    val id: String? = null,
    val type: String,
    val payload: JsonObject = buildJsonObject {},
)

@Serializable
data class ConnectionStatePayload(val state: String)

@Serializable
data class QrCodePayload(val codes: List<String>)

@Serializable
data class PairSuccessPayload(val jid: String, val platform: String)

@Serializable
data class PairErrorPayload(val error: String)

@Serializable
data class NewMessagePayload(
    @SerialName("conversation_jid") val conversationJid: String,
    @SerialName("message_id") val messageId: String,
)

@Serializable
data class ReceiptPayload(
    @SerialName("conversation_jid") val conversationJid: String,
    @SerialName("message_ids") val messageIds: List<String>,
    val type: String,
)

@Serializable
data class TypingPayload(
    val jid: String,
    @SerialName("sender_jid") val senderJid: String,
    val composing: Boolean,
)

@Serializable
data class CallOfferPayload(
    @SerialName("from_jid") val fromJid: String,
    @SerialName("call_id") val callId: String,
    @SerialName("is_video") val isVideo: Boolean,
)

@Serializable
data class HistorySyncProgressPayload(
    @SerialName("conversations_done") val conversationsDone: Int,
    @SerialName("conversations_total") val conversationsTotal: Int,
    @SerialName("messages_total") val messagesTotal: Int,
)

@Serializable
data class PairingCodeResponse(val code: String)

@Serializable
data class SendMessageResponse(
    @SerialName("message_id") val messageId: String,
    val timestamp: Long,
)

@Serializable
data class DownloadMediaResponse(
    @SerialName("local_path") val localPath: String,
)

@Serializable
data class ErrorResponse(val error: String)
