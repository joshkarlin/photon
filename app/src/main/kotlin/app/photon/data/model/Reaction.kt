package app.photon.data.model

data class Reaction(
    val messageId: String,
    val senderJid: String,
    val emoji: String,
    val timestamp: Long,
)
