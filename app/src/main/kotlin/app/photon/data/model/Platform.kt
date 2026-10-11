package app.photon.data.model

/** Messaging platform a conversation belongs to. */
enum class Platform(val label: String) {
    WHATSAPP("WhatsApp"),
    SIGNAL("Signal"),
    SMS("SMS"),
}
