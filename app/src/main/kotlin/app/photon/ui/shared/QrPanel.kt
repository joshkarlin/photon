package app.photon.ui.shared

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Shared pairing QR panel: the QR bitmap on a light quiet zone once
 * available, otherwise the error or a waiting message. Fills the available
 * area and centers its content — pass `Modifier.weight(1f)` from a Column.
 */
@Composable
fun QrPanel(
    bitmap: Bitmap?,
    error: String?,
    waitingText: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> {
                // Scanners need dark modules on a light background.
                Box(
                    modifier = Modifier
                        .background(LightThemeTokens.colors.content)
                        .padding(1f.gridUnitsAsDp()),
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier.size(16f.gridUnitsAsDp()),
                    )
                }
            }
            error != null -> LightText(error, variant = LightTextVariant.Paragraph, align = TextAlign.Center)
            else -> LightText(waitingText, variant = LightTextVariant.Paragraph, lighten = true, align = TextAlign.Center)
        }
    }
}
