package app.photon.signal

import app.photon.data.model.ContentTypes
import app.photon.signal.receiver.contentTypeForMime
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.DataMessage.Contact
import org.whispersystems.signalservice.internal.push.DataMessage.PollCreate

/**
 * One message row a Signal DataMessage becomes. [attachment] is the pointer
 * an attachment row keeps so its media can be downloaded when tapped.
 */
internal data class PlannedRow(
    val contentType: String,
    val text: String?,
    val attachment: AttachmentPointer? = null,
)

/**
 * The rows a DataMessage is stored as, in the order the thread shows them,
 * or none when it has nothing to show (a reaction, a profile key, a timer
 * update). One message can carry several things, and each gets a row of its
 * own, so the shared thread shows every one:
 *
 *  - every attachment, in order. A document's row is its file name, as on
 *    WhatsApp; a photo or video's row carries the caption (the first one
 *    only);
 *  - each shared contact, as "contact" with its name and numbers;
 *  - a poll, as readable text (see [pollText]);
 *  - the body, as a text row of its own when no photo or video took it as a
 *    caption (a document or voice note can't show one);
 *  - a sticker, when there's nothing else.
 *
 * View-once media is one [ContentTypes.VIEW_ONCE] row naming its kind. Its
 * pointer is dropped, so it can never be downloaded or saved here.
 */
internal fun plannedRows(data: DataMessage): List<PlannedRow> {
    if (data.isViewOnce == true) {
        return listOf(PlannedRow(ContentTypes.VIEW_ONCE, viewOnceKind(data.attachments.firstOrNull()?.contentType)))
    }
    val body = data.body?.takeIf { it.isNotBlank() }
    val rows = data.attachments.map { attachmentRow(it) }.toMutableList()
    val captionRow = rows.indexOfFirst { it.contentType == "image" || it.contentType == "video" }
    if (body != null && captionRow >= 0) rows[captionRow] = rows[captionRow].copy(text = body)
    for (contact in data.contact) rows += PlannedRow("contact", contactText(contact))
    data.pollCreate?.let { rows += PlannedRow("text", pollText(it)) }
    if (body != null && captionRow < 0) rows += PlannedRow("text", body)
    if (rows.isEmpty() && data.sticker != null) rows += PlannedRow("sticker", null)
    return rows
}

/**
 * What a message's notification shows, as (text, content type) for
 * MessageNotifications: view-once media by its kind, else the body, else
 * the first row's text (a contact, a poll) or kind ("[ image ]").
 */
internal fun notificationContent(rows: List<PlannedRow>, body: String?): Pair<String?, String> {
    val first = rows.first()
    return when {
        first.contentType == ContentTypes.VIEW_ONCE -> first.text to first.contentType
        !body.isNullOrBlank() -> body to "text"
        first.contentType == "text" || first.contentType == "contact" -> first.text to first.contentType
        else -> null to first.contentType
    }
}

private fun attachmentRow(pointer: AttachmentPointer): PlannedRow {
    val type = contentTypeForMime(pointer.contentType)
    return PlannedRow(type, if (type == "document") documentName(pointer.fileName) else null, pointer)
}

/** A view-once message's kind, as [ContentTypes.VIEW_ONCE] rows store it. */
internal fun viewOnceKind(mime: String?): String = when {
    mime == null -> "photo"
    mime.startsWith("video/") -> "video"
    mime.startsWith("audio/") -> "voice"
    else -> "photo"
}

/**
 * A poll as text, until polls are supported:
 *
 *     Poll: Where shall we eat?
 *     • Pizza
 *     • Thai
 *
 * A word rather than Signal's 📊: the LP3's screen is monochrome and Photon
 * uses no colour, and an emoji draws in colour. The bullets are plain text.
 */
internal fun pollText(poll: PollCreate): String {
    val question = poll.question?.trim()?.takeIf { it.isNotEmpty() }
    val header = if (question != null) "Poll: $question" else "Poll"
    val options = poll.options.map { it.trim() }.filter { it.isNotEmpty() }
    return (listOf(header) + options.map { "• $it" }).joinToString("\n")
}

/**
 * A shared contact as one line: their name and numbers, as the vCard gives
 * them ("Jane Doe · +44 7700 900123"). The name is their given, middle and
 * family names, else a nickname, else the organisation. Null when the card
 * has neither a name nor a number.
 */
internal fun contactText(contact: Contact): String? {
    val n = contact.name
    val fullName = listOfNotNull(n?.givenName, n?.middleName, n?.familyName)
        .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
    val name = fullName.ifEmpty { null }
        ?: n?.nickname?.trim()?.takeIf { it.isNotEmpty() }
        ?: contact.organization?.trim()?.takeIf { it.isNotEmpty() }
    val numbers = contact.number.mapNotNull { it.value_?.trim()?.takeIf { v -> v.isNotEmpty() } }
    return (listOfNotNull(name) + numbers).joinToString(" · ").ifEmpty { null }
}

/** Unicode's bidirectional marks, embeddings, overrides and isolates. */
private val BIDI_CONTROLS = setOf(0x061C, 0x200E, 0x200F) + (0x202A..0x202E) + (0x2066..0x2069)

private const val MAX_DOCUMENT_NAME = 200

/**
 * A received document's file name, fit to show (and to save under): no
 * folders, no control or bidirectional characters (which can disguise an
 * extension), no line breaks, and not over-long. Null when nothing is left.
 */
internal fun documentName(raw: String?): String? {
    val base = raw?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
    val cleaned = buildString {
        base.codePoints().forEach { cp ->
            when {
                Character.getType(cp) == Character.CONTROL.toInt() -> {}
                cp in BIDI_CONTROLS -> {}
                cp == 0x2028 || cp == 0x2029 -> {}
                else -> appendCodePoint(cp)
            }
        }
    }.trim()
    if (cleaned.isEmpty() || cleaned.all { it == '.' }) return null
    if (cleaned.codePointCount(0, cleaned.length) <= MAX_DOCUMENT_NAME) return cleaned
    return cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_DOCUMENT_NAME))
}
