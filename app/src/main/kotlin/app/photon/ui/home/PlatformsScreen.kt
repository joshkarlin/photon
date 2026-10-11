package app.photon.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import app.photon.ui.shared.components.MenuRow
import app.photon.ui.shared.components.MenuScreen

enum class PlatformPick { ALL, SMS, WHATSAPP, SIGNAL }

@Composable
fun PlatformsScreen(
    onPick: (PlatformPick) -> Unit,
) {
    // Picker has no back affordance — the user must choose a scope.
    // Intercept system back so the only way out is via a selection.
    BackHandler(enabled = true) { /* no-op */ }

    MenuScreen(title = null, onBack = null) {
        MenuRow("ALL CHATS") { onPick(PlatformPick.ALL) }
        MenuRow("SMS") { onPick(PlatformPick.SMS) }
        MenuRow("WHATSAPP") { onPick(PlatformPick.WHATSAPP) }
        MenuRow("SIGNAL") { onPick(PlatformPick.SIGNAL) }
    }
}
