package app.photon.signal

import app.photon.signal.db.SignalMessageDatabase
import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import java.io.File

/** Where an outgoing DataMessage goes. */
internal sealed interface Recipient {
    val conversationJid: String

    /** A 1:1 conversation, keyed by the contact's ACI. */
    data class Dm(override val conversationJid: String, val aci: ServiceId.ACI) : Recipient

    /** A GroupV2 conversation, sent with its master key (stored once a group message was seen). */
    data class Group(override val conversationJid: String, val meta: SignalMessageDatabase.GroupMeta) : Recipient
}

/** What an outgoing DataMessage carries. */
internal sealed interface OutgoingContent {
    /** Text and/or attachments, optionally quoting the message keyed [replyPrefix]. */
    data class Message(
        val text: String?,
        val replyPrefix: String?,
        val attachments: List<SignalServiceAttachment> = emptyList(),
    ) : OutgoingContent

    data class Reaction(val reaction: SignalServiceDataMessage.Reaction) : OutgoingContent

    /** Delete-for-everyone of the message sent at [targetSentTimestamp]. */
    data class RemoteDelete(val targetSentTimestamp: Long) : OutgoingContent
}

/** The network side of a send: the message (and its sync transcript), and attachment uploads. */
internal interface SignalTransport {
    /** Throws if the message didn't reach the recipient (or, in a group, any member). */
    fun send(recipient: Recipient, content: OutgoingContent, timestamp: Long)

    /** Upload [file] to the CDN as an attachment named [fileName]. */
    fun upload(file: File, mimeType: String, voiceNote: Boolean, fileName: String): SignalServiceAttachmentPointer
}

/**
 * The name an attachment is uploaded under: a document's own name (the one
 * the user's file had), else the staged file's.
 */
internal fun attachmentFileName(contentType: String, documentName: String?, file: File): String =
    documentName?.takeIf { contentType == "document" && it.isNotBlank() } ?: file.name

/** A mime type for a stored row that has none, from its content type. */
internal fun defaultMimeFor(contentType: String): String = when (contentType) {
    "image" -> "image/jpeg"
    "video" -> "video/mp4"
    "audio" -> "audio/ogg"
    "sticker" -> "image/webp"
    else -> "application/octet-stream"
}
