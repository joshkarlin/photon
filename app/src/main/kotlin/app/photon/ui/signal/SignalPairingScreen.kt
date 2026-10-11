package app.photon.ui.signal

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.photon.service.PhotonService
import app.photon.ui.shared.QrPanel
import app.photon.ui.shared.components.backButton
import app.photon.ui.shared.generateQrBitmap
import app.photon.ui.theme.PhotonGrid
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

@Composable
fun SignalPairingScreen(onPaired: () -> Unit, onBack: () -> Unit) {
    val manager = PhotonService._signalManager ?: return
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Connecting...") }
    var paired by remember { mutableStateOf(false) }

    // Navigate on main thread when pairing completes
    LaunchedEffect(paired) {
        if (paired) {
            PhotonService._signalReceiver?.start()
            onPaired()
        }
    }

    LaunchedEffect(Unit) {
        manager.startProvisioning(
            onQrUrl = { url ->
                qrBitmap = generateQrBitmap(url)
                status = "Scan with your primary phone"
            },
            onComplete = { success, errorMsg ->
                if (success) {
                    paired = true
                } else {
                    error = errorMsg ?: "Pairing failed"
                    status = "Failed"
                }
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose { manager.cancelProvisioning() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(leftButton = backButton(onBack), center = LightTopBarCenter.Text("Link Signal"))

        // Instructions
        LightText(
            text = "Signal > Linked Devices > Link New Device",
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(
                horizontal = PhotonGrid.MENU_MARGIN.gridUnitsAsDp(),
                vertical = 1f.gridUnitsAsDp(),
            ),
        )

        // QR code
        QrPanel(
            bitmap = qrBitmap,
            error = error,
            waitingText = status,
            modifier = Modifier.weight(1f).padding(horizontal = PhotonGrid.MENU_MARGIN.gridUnitsAsDp()),
        )
    }
}
