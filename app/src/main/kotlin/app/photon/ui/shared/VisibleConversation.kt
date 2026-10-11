package app.photon.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.photon.service.NotificationHelper

/**
 * Marks [conversationJid] as on screen while the hosting lifecycle is resumed,
 * so new messages in it don't post a notification, and clears any existing
 * notification for it on each resume.
 */
@Composable
fun VisibleConversationEffect(conversationJid: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(conversationJid, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    NotificationHelper.setVisibleConversation(conversationJid)
                    NotificationHelper.cancelForConversation(context, conversationJid)
                }
                Lifecycle.Event.ON_PAUSE ->
                    NotificationHelper.clearVisibleConversation(conversationJid)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            NotificationHelper.clearVisibleConversation(conversationJid)
        }
    }
}
