package app.photon.nav

import android.content.Intent
import app.photon.data.model.Platform
import app.photon.service.NotificationHelper

/** A request to open one conversation, e.g. from tapping its notification. */
data class ChatDeepLink(val platform: Platform, val conversationJid: String) {
    val route: String get() = Routes.chat(platform, conversationJid)

    companion object {
        fun from(intent: Intent?): ChatDeepLink? {
            val platform = Routes.platformOf(intent?.getStringExtra(NotificationHelper.EXTRA_PLATFORM)) ?: return null
            val jid = intent?.getStringExtra(NotificationHelper.EXTRA_CONVERSATION)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            return ChatDeepLink(platform, jid)
        }
    }
}
