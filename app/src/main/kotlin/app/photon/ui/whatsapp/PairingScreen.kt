package app.photon.ui.whatsapp

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import app.photon.data.model.PairingCodeResponse
import app.photon.data.model.QrCodePayload
import app.photon.data.model.string
import app.photon.service.Sessions
import app.photon.ui.shared.QrPanel
import app.photon.ui.shared.components.LightInputField
import app.photon.ui.shared.components.backButton
import app.photon.ui.shared.generateQrBitmap
import app.photon.ui.theme.PhotonColors
import app.photon.ui.theme.PhotonGrid
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

private enum class PairingMode { CODE, QR }

@Composable
fun PairingScreen(onPaired: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val session = Sessions.whatsapp
    val bridge = session?.bridge
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
                val resp = bridge?.requestPairingCode(phone)
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
        val cached = session?.cachedQrCode()
        if (cached != null) qrBitmap = generateQrBitmap(cached)
    }

    LaunchedEffect(bridge) {
        bridge?.events?.collect { evt ->
            when (evt.type) {
                "pair_success" -> {
                    session?.clearQrCode()
                    onPaired()
                }
                "qr_code" -> {
                    try {
                        val payload = Json.decodeFromJsonElement<QrCodePayload>(evt.payload)
                        val qrText = payload.codes.firstOrNull()
                        if (qrText != null) {
                            session?.cacheQrCode(qrText)
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
                try { bridge?.requestQr() } catch (_: Exception) {}
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(leftButton = backButton(onBack), center = LightTopBarCenter.Text("Link WhatsApp"))
                Instructions("WhatsApp > Linked Devices > Link a Device")
                QrPanel(
                    bitmap = qrBitmap,
                    error = error,
                    waitingText = "Waiting for QR code…",
                    modifier = Modifier.weight(1f).padding(horizontal = PhotonGrid.MENU_MARGIN.gridUnitsAsDp()),
                )
                LightBottomBar(
                    items = listOf(LightBarButton.Text(text = "USE PAIRING CODE", onClick = { mode = PairingMode.CODE })),
                )
            }
        }

        PairingMode.CODE -> {
            // Code mode — scrollable for keyboard
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(leftButton = backButton(onBack), center = LightTopBarCenter.Text("Link WhatsApp"))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = PhotonGrid.MENU_MARGIN.gridUnitsAsDp()),
                    verticalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = "Enter phone number with country code",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                    )
                    LightInputField(
                        value = phone,
                        onValueChange = { phone = it },
                        placeholder = "+1234567890",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { requestCode() }),
                    )

                    val canRequest = phone.isNotBlank() && !loading
                    LightText(
                        text = if (loading) "REQUESTING…" else "GET CODE",
                        variant = LightTextVariant.Copy,
                        color = if (canRequest) null else PhotonColors.Faint,
                        modifier = Modifier
                            .lightClickable(enabled = canRequest) { requestCode() }
                            .padding(vertical = PhotonGrid.MENU_ROW_PADDING.gridUnitsAsDp()),
                    )

                    code?.let { c ->
                        val formatted = if (c.length == 8) "${c.substring(0, 4)}-${c.substring(4)}" else c
                        LightText(
                            text = formatted,
                            variant = LightTextVariant.Subtitle,
                            monospace = true,
                            align = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        LightText(
                            text = "WhatsApp > Linked Devices > Link with phone number",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                        )
                    }

                    error?.let { LightText(text = it, variant = LightTextVariant.Paragraph) }
                }
                LightBottomBar(
                    items = listOf(LightBarButton.Text(text = "USE QR CODE", onClick = { mode = PairingMode.QR })),
                )
            }
        }
    }
}

@Composable
private fun Instructions(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier.padding(
            horizontal = PhotonGrid.MENU_MARGIN.gridUnitsAsDp(),
            vertical = 1f.gridUnitsAsDp(),
        ),
    )
}
