package app.photon.ui.whatsapp

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.photon.data.model.PairingCodeResponse
import app.photon.data.model.QrCodePayload
import app.photon.data.model.string
import app.photon.service.PhotonService
import app.photon.ui.shared.QrPanel
import app.photon.ui.shared.components.PhotonHeader
import app.photon.ui.shared.generateQrBitmap
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

private enum class PairingMode { CODE, QR }

@Composable
fun PairingScreen(onPaired: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ws = PhotonService._wsClient
    var mode by remember { mutableStateOf(PairingMode.QR) }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    // Request a pairing code for the entered phone number (CODE mode).
    fun requestCode() {
        if (phone.isBlank() || loading) return
        loading = true; error = null
        scope.launch {
            try {
                val resp = ws?.requestPairingCode(phone)
                val payload = resp?.payload
                if (payload != null) {
                    code = Json.decodeFromJsonElement<PairingCodeResponse>(payload).code
                }
            } catch (e: Exception) { error = e.message }
            finally { loading = false }
        }
    }

    // Load cached QR on entry, clear cache on exit
    LaunchedEffect(Unit) {
        val cached = PhotonService.getCachedQr()
        if (cached != null) qrBitmap = generateQrBitmap(cached)
    }

    LaunchedEffect(ws) {
        ws?.events?.collect { evt ->
            when (evt.type) {
                "pair_success" -> {
                    PhotonService.clearQrCache()
                    onPaired()
                }
                "qr_code" -> {
                    try {
                        val payload = Json.decodeFromJsonElement<QrCodePayload>(evt.payload)
                        val qrText = payload.codes.firstOrNull()
                        if (qrText != null) {
                            PhotonService.cacheQrCode(qrText)
                            qrBitmap = generateQrBitmap(qrText)
                        }
                    } catch (_: Exception) {}
                }
                "pair_error" -> {
                    val msg = evt.string("error") ?: ""
                    // Ignore "already connected" — just means pairing is in progress
                    if (!msg.contains("already connected", ignoreCase = true)) {
                        error = msg
                    }
                }
            }
        }
    }

    when (mode) {
        PairingMode.QR -> {
            // QR mode — compact, no scrolling
            LaunchedEffect(Unit) {
                try { ws?.requestQr() } catch (_: Exception) {}
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .padding(horizontal = 20.dp),
            ) {
                PhotonHeader("PAIR WHATSAPP", onBack, horizontalPadding = 0.dp)
                HorizontalDivider(color = Color(0xFF1A1A1A))

                Text(
                    text = "WhatsApp > Linked Devices > Link a Device",
                    fontSize = 13.sp,
                    color = Color(0xFF666666),
                    modifier = Modifier.padding(vertical = 16.dp),
                )

                QrPanel(
                    bitmap = qrBitmap,
                    error = error,
                    waitingText = "Waiting for QR code...",
                    modifier = Modifier.weight(1f),
                )

                // Mode toggle at bottom
                HorizontalDivider(color = Color(0xFF1A1A1A))
                Text(
                    text = "USE PAIRING CODE INSTEAD",
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                    color = Color(0xFF666666),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { mode = PairingMode.CODE }
                        .padding(vertical = 16.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }

        PairingMode.CODE -> {
            // Code mode — scrollable for keyboard
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                PhotonHeader("PAIR WHATSAPP", onBack, horizontalPadding = 0.dp)
                HorizontalDivider(color = Color(0xFF1A1A1A))
                Spacer(Modifier.height(24.dp))

                Text(
                    text = "Enter phone number with country code",
                    fontSize = 13.sp,
                    color = Color(0xFF666666),
                )
                Spacer(Modifier.height(12.dp))

                TextField(
                    value = phone,
                    onValueChange = { phone = it },
                    placeholder = { Text("+1234567890", color = Color(0xFF333333)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    keyboardActions = KeyboardActions(onDone = { requestCode() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0D0D0D),
                        unfocusedContainerColor = Color(0xFF0D0D0D),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = if (loading) "REQUESTING..." else "GET CODE",
                    fontSize = 18.sp,
                    letterSpacing = 2.sp,
                    color = if (loading) Color(0xFF666666) else Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = phone.isNotBlank() && !loading) { requestCode() }
                        .padding(vertical = 22.dp),
                )

                if (code != null) {
                    HorizontalDivider(color = Color(0xFF111111))
                    Spacer(Modifier.height(24.dp))
                    val formatted = if (code!!.length == 8) "${code!!.substring(0, 4)}-${code!!.substring(4)}" else code!!
                    Text(
                        text = formatted,
                        fontSize = 36.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "WhatsApp > Linked Devices > Link with phone number",
                        fontSize = 13.sp,
                        color = Color(0xFF666666),
                    )
                }

                if (error != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(text = error!!, fontSize = 14.sp, color = Color(0xFFCC4444))
                }

                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = Color(0xFF1A1A1A))
                Text(
                    text = "USE QR CODE INSTEAD",
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                    color = Color(0xFF666666),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { mode = PairingMode.QR }
                        .padding(vertical = 16.dp),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

