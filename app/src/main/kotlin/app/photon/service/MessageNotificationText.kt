package app.photon.service

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

/** What a notification shows of a message: its text, else its kind ("[ image ]"). */
fun notificationPreview(text: String?, contentType: String?): String? =
    text?.takeIf { it.isNotBlank() }
        ?: contentType?.takeIf { it.isNotBlank() && it != "text" }?.let { "[ $it ]" }
