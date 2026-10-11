package app.photon.nav

import android.net.Uri
import app.photon.data.model.Platform

object Routes {
    /** The chat list: every platform's chats, or one platform's ([chats]). */
    const val CHATS = "chats?platform={platform}"

    /** One conversation, on any platform. */
    const val CHAT = "chat/{platform}/{jid}"

    const val PLATFORMS = "platforms"
    const val WHATSAPP_PAIRING = "whatsapp/pairing"
    const val SIGNAL_PAIRING = "signal/pairing"
    const val CONTACT = "contact/{phone}/{name}"
    const val SETTINGS = "settings"

    fun chats(filter: Platform?) = if (filter == null) "chats" else "chats?platform=${filter.name}"

    fun chat(platform: Platform, jid: String) = "chat/${platform.name}/${Uri.encode(jid)}"

    fun contact(phone: String, name: String) = "contact/$phone/$name"

    /** The platform a route names, or null for none or one it doesn't know. */
    fun platformOf(arg: String?): Platform? = Platform.entries.firstOrNull { it.name == arg }
}

/**
 * Where picking [filter] in the platform picker goes: its chats, unless it
 * has to be linked first. WhatsApp pairs only once the bridge reports no
 * session ("logged_out"): a paired account that is connecting,
 * reconnecting or not yet reported on belongs in its chats, which show the
 * connection state. Signal pairs when the device isn't linked.
 */
internal fun pickedRoute(filter: Platform?, whatsappState: String?, signalLinked: Boolean): String = when {
    filter == Platform.WHATSAPP && whatsappState == "logged_out" -> Routes.WHATSAPP_PAIRING
    filter == Platform.SIGNAL && !signalLinked -> Routes.SIGNAL_PAIRING
    else -> Routes.chats(filter)
}
