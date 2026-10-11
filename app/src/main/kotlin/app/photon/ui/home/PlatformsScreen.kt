package app.photon.ui.home

import androidx.compose.runtime.Composable
import app.photon.data.model.Platform
import app.photon.ui.shared.components.MenuRow
import app.photon.ui.shared.components.MenuScreen

/**
 * The chat list's filter: every platform's chats (null) or one platform's.
 * Opened from the list's bottom bar; Back returns to the list unchanged.
 */
@Composable
fun PlatformsScreen(
    onPick: (Platform?) -> Unit,
    onBack: () -> Unit,
) {
    MenuScreen(title = "Chats", onBack = onBack) {
        MenuRow("All Chats") { onPick(null) }
        listOf(Platform.SMS, Platform.WHATSAPP, Platform.SIGNAL).forEach { platform ->
            MenuRow(platform.label) { onPick(platform) }
        }
    }
}
