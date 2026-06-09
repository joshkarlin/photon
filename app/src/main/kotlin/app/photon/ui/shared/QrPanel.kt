package app.photon.ui.shared

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared pairing QR panel: white rounded card with the QR bitmap once
 * available, an error in red, or a waiting message. Fills the available
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
                Box(
                    modifier = Modifier
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier.size(220.dp),
                    )
                }
            }
            error != null -> Text(text = error, fontSize = 14.sp, color = Color(0xFFCC4444))
            else -> Text(waitingText, fontSize = 14.sp, color = Color(0xFF666666))
        }
    }
}
