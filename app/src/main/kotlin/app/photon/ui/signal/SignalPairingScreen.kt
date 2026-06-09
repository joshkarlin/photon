package app.photon.ui.signal

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.photon.service.PhotonService
import app.photon.ui.shared.QrPanel
import app.photon.ui.shared.components.PhotonHeader
import app.photon.ui.shared.generateQrBitmap

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
            .background(Color.Black)
            .padding(horizontal = 20.dp),
    ) {
        // Header
        PhotonHeader("LINK SIGNAL", onBack, horizontalPadding = 0.dp)
        HorizontalDivider(color = Color(0xFF1A1A1A))

        // Instructions
        Text(
            text = "Signal > Linked Devices > Link New Device",
            fontSize = 13.sp,
            color = Color(0xFF666666),
            modifier = Modifier.padding(vertical = 16.dp),
        )

        // QR code
        QrPanel(
            bitmap = qrBitmap,
            error = error,
            waitingText = status,
            modifier = Modifier.weight(1f),
        )
    }
}

