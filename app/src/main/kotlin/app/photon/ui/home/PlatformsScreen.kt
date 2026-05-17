package app.photon.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class PlatformPick { ALL, SMS, WHATSAPP, SIGNAL }

@Composable
fun PlatformsScreen(
    onPick: (PlatformPick) -> Unit,
) {
    // Picker has no top bar and no back affordance — the user must choose a
    // scope. Intercept system back so the only way out is via a selection.
    BackHandler(enabled = true) { /* no-op */ }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.weight(1f))
        MenuItem("ALL") { onPick(PlatformPick.ALL) }
        MenuItem("SMS") { onPick(PlatformPick.SMS) }
        MenuItem("WHATSAPP") { onPick(PlatformPick.WHATSAPP) }
        MenuItem("SIGNAL") { onPick(PlatformPick.SIGNAL) }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 18.sp,
        letterSpacing = 2.sp,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp),
    )
}
