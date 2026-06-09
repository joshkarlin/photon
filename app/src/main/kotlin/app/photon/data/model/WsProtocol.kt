package app.photon.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

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

// Typed payload accessors — use these instead of payload[key]?.toString()?.trim('"'),
// which corrupts values containing quotes and turns JSON null into "null".
fun WsEvent.string(key: String): String? =
    try { payload[key]?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }

fun WsEvent.int(key: String): Int? =
    try { payload[key]?.jsonPrimitive?.intOrNull } catch (_: Exception) { null }

fun WsEvent.boolean(key: String): Boolean? =
    try { payload[key]?.jsonPrimitive?.booleanOrNull } catch (_: Exception) { null }

@Serializable
data class QrCodePayload(val codes: List<String>)

@Serializable
data class PairingCodeResponse(val code: String)

@Serializable
data class DownloadMediaResponse(
    @SerialName("local_path") val localPath: String,
)
