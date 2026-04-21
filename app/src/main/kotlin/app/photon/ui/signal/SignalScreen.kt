package app.photon.ui.signal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.photon.service.PhotonService

/**
 * Routing screen — checks if Signal is linked and navigates accordingly.
 */
@Composable
fun SignalScreen(
    onPaired: () -> Unit,
    onNotPaired: () -> Unit,
    onBack: () -> Unit,
) {
    val credentials = PhotonService._signalCredentials

    LaunchedEffect(Unit) {
        if (credentials?.isRegistered() == true) {
            onPaired()
        } else {
            onNotPaired()
        }
    }
}
