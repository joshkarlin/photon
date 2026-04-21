package app.photon.ui.signal

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.photon.service.PhotonService
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 12.dp),
        ) {
            Text("<", fontSize = 18.sp, color = Color(0xFF666666),
                modifier = Modifier.align(Alignment.CenterStart).clickable(onClick = onBack).padding(end = 16.dp))
            Text("LINK SIGNAL", fontSize = 13.sp, letterSpacing = 3.sp, color = Color(0xFF666666),
                modifier = Modifier.align(Alignment.Center))
        }
        HorizontalDivider(color = Color(0xFF1A1A1A))

        // Instructions
        Text(
            text = "Signal > Linked Devices > Link New Device",
            fontSize = 13.sp,
            color = Color(0xFF666666),
            modifier = Modifier.padding(vertical = 16.dp),
        )

        // QR code
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (qrBitmap != null) {
                Box(
                    modifier = Modifier
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                ) {
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier.size(220.dp),
                    )
                }
            } else if (error != null) {
                Text(text = error!!, fontSize = 14.sp, color = Color(0xFFCC4444))
            } else {
                Text(status, fontSize = 14.sp, color = Color(0xFF666666))
            }
        }
    }
}

