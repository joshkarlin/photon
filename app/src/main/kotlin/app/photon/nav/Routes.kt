package app.photon.nav

import android.net.Uri
import androidx.navigation.NavController
import app.photon.data.model.Platform

object Routes {
    /** The chat list: every platform's chats, or one platform's ([chats]). */
    const val CHATS = "chats?platform={platform}"

    /** One conversation, on any platform. */
    const val CHAT = "chat/{platform}/{jid}"

    const val PLATFORMS = "platforms"

    /** Chats archived on the user's phone, every platform's ([openArchived]). */
    const val ARCHIVED = "archived"
    const val WHATSAPP_PAIRING = "whatsapp/pairing"
    const val SIGNAL_PAIRING = "signal/pairing"
    const val CONTACT = "contact/{phone}/{name}"
    const val SETTINGS = "settings"

    /** Find someone and start (or open) a chat with them ([openNewMessage]). */
    const val NEW_MESSAGE = "new-message"

    fun chats(filter: Platform?) = if (filter == null) "chats" else "chats?platform=${filter.name}"

    fun chat(platform: Platform, jid: String) = "chat/${platform.name}/${Uri.encode(jid)}"

    fun contact(phone: String, name: String) = "contact/$phone/$name"

    /** The platform a route names, or null for none or one it doesn't know. */
    fun platformOf(arg: String?): Platform? = Platform.entries.firstOrNull { it.name == arg }
}

/** Open New message over the current screen, e.g. from the chat list's bottom bar. */
fun NavController.openNewMessage() = navigate(Routes.NEW_MESSAGE) { launchSingleTop = true }

/**
 * Open a chat picked in New message in its place, so Back from the chat
 * returns to the chat list rather than to the search.
 */
internal fun NavController.openChatFromNewMessage(platform: Platform, jid: String) =
    navigate(Routes.chat(platform, jid)) { popUpTo(Routes.NEW_MESSAGE) { inclusive = true } }

/**
 * Open the archived chats in the platform picker's place, so Back returns
 * to the chat list the picker was opened from.
 */
fun NavController.openArchived() = navigate(Routes.ARCHIVED) {
    popUpTo(Routes.PLATFORMS) { inclusive = true }
    launchSingleTop = true
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
