package app.photon.data.model

data class Participant(
    val conversationJid: String,
    val jid: String,
    val displayName: String?,
    val role: String,
)
