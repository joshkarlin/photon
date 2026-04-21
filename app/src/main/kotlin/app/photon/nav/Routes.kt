package app.photon.nav

object Routes {
    const val HOME = "home"
    const val WHATSAPP_PAIRING = "whatsapp/pairing"
    const val WHATSAPP_CHATS = "whatsapp/chats"
    const val WHATSAPP_CHAT = "whatsapp/chat/{jid}"
    const val SIGNAL = "signal"
    const val SIGNAL_PAIRING = "signal/pairing"
    const val SIGNAL_CHATS = "signal/chats"
    const val SIGNAL_CHAT = "signal/chat/{jid}"
    const val ALL_CHATS = "all_chats"
    const val SMS = "sms"
    const val SMS_CHATS = "sms/chats"
    const val SMS_CHAT = "sms/chat/{address}"
    const val SETTINGS = "settings"

    fun smsChat(address: String) = "sms/chat/$address"

    fun chat(jid: String) = "whatsapp/chat/$jid"
    fun signalChat(jid: String) = "signal/chat/$jid"
}
