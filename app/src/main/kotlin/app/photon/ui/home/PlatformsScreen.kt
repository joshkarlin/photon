package app.photon.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import app.photon.data.model.Platform
import app.photon.ui.shared.components.MenuRow
import app.photon.ui.shared.components.MenuScreen

/** The chat list's scope: every platform's chats (null) or one platform's. */
@Composable
fun PlatformsScreen(
    onPick: (Platform?) -> Unit,
) {
    // Picker has no back affordance — the user must choose a scope.
    // Intercept system back so the only way out is via a selection.
    BackHandler(enabled = true) { /* no-op */ }

    MenuScreen(title = null, onBack = null) {
        MenuRow("ALL CHATS") { onPick(null) }
        MenuRow("SMS") { onPick(Platform.SMS) }
        MenuRow("WHATSAPP") { onPick(Platform.WHATSAPP) }
        MenuRow("SIGNAL") { onPick(Platform.SIGNAL) }
    }
}
