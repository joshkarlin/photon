package app.photon.nav

import android.net.Uri
import app.photon.data.model.Platform

object Routes {
    const val WHATSAPP_PAIRING = "whatsapp/pairing"
    const val WHATSAPP_CHATS = "whatsapp/chats"
    const val SIGNAL = "signal"
    const val SIGNAL_PAIRING = "signal/pairing"
    const val SIGNAL_CHATS = "signal/chats"
    const val ALL_CHATS = "all_chats"
    const val PLATFORMS = "platforms"
    const val CONTACT = "contact/{phone}/{name}"

    fun contact(phone: String, name: String) = "contact/$phone/$name"
    const val SMS_CHATS = "sms/chats"
    const val SETTINGS = "settings"

    /** One conversation, on any platform. */
    const val CHAT = "chat/{platform}/{jid}"

    fun chat(platform: Platform, jid: String) = "chat/${platform.name}/${Uri.encode(jid)}"

    /** The platform a [CHAT] route names, or null for one it doesn't know. */
    fun platformOf(arg: String?): Platform? = Platform.entries.firstOrNull { it.name == arg }
}
