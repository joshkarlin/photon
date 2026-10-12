package app.photon.service

import app.photon.data.model.ContentTypes

/** A message notification's title and body; a null body shows "New message". */
data class MessageNotificationText(val title: String, val body: String?)

/**
 * The one formatter for message notifications on every platform. A DM is
 * titled with the person; a group is titled with the group, and the body
 * says who wrote ("Alice: see you at 8").
 *
 * @param senderName who wrote, as DisplayNames resolves them; null when unknown
 * @param preview what [notificationPreview] makes of the message
 */
fun messageNotificationText(
    conversationTitle: String,
    isGroup: Boolean,
    senderName: String?,
    preview: String?,
): MessageNotificationText = MessageNotificationText(
    title = conversationTitle,
    body = if (isGroup && !senderName.isNullOrBlank()) "$senderName: ${preview ?: NotificationHelper.NEW_MESSAGE}" else preview,
)

/**
 * What a notification (or a reply quote) shows of a message: its text, else
 * its kind ("[ image ]"). View-once media, messages still on their way and
 * locations say what they are, whatever their text holds ([ContentTypes]).
 */
fun notificationPreview(text: String?, contentType: String?): String? = when (contentType) {
    ContentTypes.VIEW_ONCE -> viewOnceLabel(text)
    ContentTypes.UNAVAILABLE -> UNAVAILABLE_LABEL
    ContentTypes.LOCATION -> locationLabel(text)
    else -> text?.takeIf { it.isNotBlank() }
        ?: contentType?.takeIf { it.isNotBlank() && it != "text" }?.let { "[ $it ]" }
}

/**
 * A view-once message by its kind, a VIEW_ONCE row's text_body: "view-once
 * photo", "view-once video", "view-once voice message".
 */
fun viewOnceLabel(kind: String?): String = when (kind?.trim()?.lowercase()) {
    "photo" -> "view-once photo"
    "video" -> "view-once video"
    "voice" -> "view-once voice message"
    else -> "view-once message"
}

/** A message the platform hasn't delivered to this device yet (UNAVAILABLE). */
const val UNAVAILABLE_LABEL = "waiting for this message"

/** A shared location: "Location", with its name or address when it has one. */
fun locationLabel(label: String?): String =
    label?.trim()?.takeIf { it.isNotEmpty() }?.let { "Location · $it" } ?: "Location"
