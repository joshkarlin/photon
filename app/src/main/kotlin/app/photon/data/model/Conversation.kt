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
) {
    /**
     * WhatsApp pseudo-chats — status broadcasts ("status@broadcast"), the
     * server's "0@..." metadata chat, and broadcast lists. One predicate so
     * chat lists and notifications can't drift on what gets hidden/muted.
     */
    val isPseudoChat: Boolean
        get() = isPseudoWhatsAppJid(jid)
}

fun isPseudoWhatsAppJid(jid: String): Boolean =
    jid.startsWith("status@") || jid.startsWith("0@") || jid.contains("@broadcast")
