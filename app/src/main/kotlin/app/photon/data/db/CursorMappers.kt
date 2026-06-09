package app.photon.data.db

import android.database.Cursor
import app.photon.data.model.Conversation
import app.photon.data.model.Message
import app.photon.data.model.Participant
import app.photon.data.model.Reaction

/**
 * Cursor → model mappers shared by PhotonDatabase (WhatsApp, Go-bridge schema)
 * and SignalMessageDatabase. Both schemas use identical column names, so a fix
 * here (e.g. a new Message column) lands in both databases at once.
 */

fun Cursor.toConversations(): List<Conversation> {
    val list = mutableListOf<Conversation>()
    while (moveToNext()) list.add(toConversation())
    return list
}

fun Cursor.toConversation(): Conversation {
    return Conversation(
        jid = getString(getColumnIndexOrThrow("jid")),
        name = getStringOrNull("name"),
        isGroup = getInt(getColumnIndexOrThrow("is_group")) == 1,
        lastMessageId = getStringOrNull("last_message_id"),
        lastTimestamp = getLong(getColumnIndexOrThrow("last_timestamp")),
        unreadCount = getInt(getColumnIndexOrThrow("unread_count")),
        isMuted = getInt(getColumnIndexOrThrow("is_muted")) == 1,
        avatarUrl = getStringOrNull("avatar_url"),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
    )
}

fun Cursor.toMessages(): List<Message> {
    val list = mutableListOf<Message>()
    while (moveToNext()) list.add(toMessage())
    return list
}

fun Cursor.toMessage(): Message {
    return Message(
        id = getString(getColumnIndexOrThrow("id")),
        conversationJid = getString(getColumnIndexOrThrow("conversation_jid")),
        senderJid = getString(getColumnIndexOrThrow("sender_jid")),
        timestamp = getLong(getColumnIndexOrThrow("timestamp")),
        contentType = getString(getColumnIndexOrThrow("content_type")),
        textBody = getStringOrNull("text_body"),
        mediaUrl = getStringOrNull("media_url"),
        mediaMime = getStringOrNull("media_mime"),
        mediaSize = getLongOrNull("media_size"),
        thumbnailPath = getStringOrNull("thumbnail_path"),
        stickerPackId = getStringOrNull("sticker_pack_id"),
        replyToId = getStringOrNull("reply_to_id"),
        editVersion = getInt(getColumnIndexOrThrow("edit_version")),
        isFromMe = getInt(getColumnIndexOrThrow("is_from_me")) == 1,
        status = getString(getColumnIndexOrThrow("status")),
    )
}

fun Cursor.toReaction(): Reaction {
    return Reaction(
        messageId = getString(getColumnIndexOrThrow("message_id")),
        senderJid = getString(getColumnIndexOrThrow("sender_jid")),
        emoji = getString(getColumnIndexOrThrow("emoji")),
        timestamp = getLong(getColumnIndexOrThrow("timestamp")),
    )
}

fun Cursor.toParticipants(): List<Participant> {
    val list = mutableListOf<Participant>()
    while (moveToNext()) {
        list.add(
            Participant(
                conversationJid = getString(getColumnIndexOrThrow("conversation_jid")),
                jid = getString(getColumnIndexOrThrow("jid")),
                displayName = getStringOrNull("display_name"),
                role = getString(getColumnIndexOrThrow("role")),
            )
        )
    }
    return list
}

fun Cursor.getStringOrNull(column: String): String? {
    val idx = getColumnIndex(column)
    return if (idx >= 0 && !isNull(idx)) getString(idx) else null
}

fun Cursor.getLongOrNull(column: String): Long? {
    val idx = getColumnIndex(column)
    return if (idx >= 0 && !isNull(idx)) getLong(idx) else null
}
