package app.photon.data.model

/**
 * Message content types every platform stores in `content_type` and the UI
 * renders. The older types ("text", "image", "video", "audio", "document",
 * "sticker", "contact", "call") are still written as literals.
 */
object ContentTypes {
    /** A place. text_body: its name or address (may be null). media_url: `geo:<lat>,<lng>`. */
    const val LOCATION = "location"

    /**
     * A centred informational line, such as "Disappearing messages · 1 week".
     * text_body: the line. Never unread, never notifies, never offers actions.
     */
    const val SYSTEM = "system"

    /**
     * View-once media Photon deliberately doesn't show. text_body: "photo",
     * "video" or "voice". Counts as unread and notifies; never downloaded,
     * thumbnailed or saved.
     */
    const val VIEW_ONCE = "view_once"

    /**
     * A message the platform couldn't deliver to this linked device yet (e.g.
     * a WhatsApp message that failed to decrypt). Replaced in place if the
     * phone resends it. text_body: null.
     */
    const val UNAVAILABLE = "unavailable"
}
