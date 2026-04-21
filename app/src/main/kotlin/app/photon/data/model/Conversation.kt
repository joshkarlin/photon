package app.photon.data.model

data class Conversation(
    val jid: String,
    val name: String?,
    val isGroup: Boolean,
    val lastMessageId: String?,
    val lastTimestamp: Long,
    val unreadCount: Int,
    val isMuted: Boolean,
    val avatarUrl: String?,
    val updatedAt: Long,
    val lastMessagePreview: String? = null,
)
