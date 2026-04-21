package app.photon.data.model

data class Message(
    val id: String,
    val conversationJid: String,
    val senderJid: String,
    val timestamp: Long,
    val contentType: String,
    val textBody: String?,
    val mediaUrl: String?,
    val mediaMime: String?,
    val mediaSize: Long?,
    val thumbnailPath: String?,
    val stickerPackId: String?,
    val replyToId: String?,
    val editVersion: Int,
    val isFromMe: Boolean,
    val status: String,
    val reactions: List<Reaction> = emptyList(),
    val replyToPreview: Message? = null,
)
