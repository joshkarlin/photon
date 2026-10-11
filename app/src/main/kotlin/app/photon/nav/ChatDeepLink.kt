package app.photon.nav

import android.content.Intent
import app.photon.data.model.Platform
import app.photon.service.NotificationHelper

/** A request to open one conversation, e.g. from tapping its notification. */
data class ChatDeepLink(val platform: Platform, val conversationJid: String) {
    companion object {
        fun from(intent: Intent?): ChatDeepLink? {
            val platform = intent?.getStringExtra(NotificationHelper.EXTRA_PLATFORM)
                ?.let { name -> Platform.entries.firstOrNull { it.name == name } }
                ?: return null
            val jid = intent.getStringExtra(NotificationHelper.EXTRA_CONVERSATION)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            return ChatDeepLink(platform, jid)
        }
    }
}
