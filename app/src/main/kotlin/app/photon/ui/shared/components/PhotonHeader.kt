package app.photon.ui.shared.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Standard LP3 screen header: a `<` back glyph (18sp, #666666) left-aligned
 * and a centered title (13sp, 3sp letter-spacing, #666666). Used by every
 * screen — same dimensions everywhere (top=16, bottom=12, horizontal=20).
 *
 * @param title centered title text (caller uppercases if needed); null hides it
 * @param onBack back/dismiss tap handler; null hides the `<` glyph
 * @param horizontalPadding pass 0.dp when the parent already pads horizontally
 * @param onTitleClick makes the title tappable (e.g. open contact, switch list)
 * @param trailing optional right-aligned content (align with Alignment.CenterEnd)
 */
@Composable
fun PhotonHeader(
    title: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 20.dp,
    onTitleClick: (() -> Unit)? = null,
    trailing: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .padding(top = 16.dp, bottom = 12.dp),
    ) {
        if (onBack != null) {
            Text(
                "<", fontSize = 18.sp, color = Color(0xFF666666),
                modifier = Modifier.align(Alignment.CenterStart)
                    .clickable(onClick = onBack)
                    .padding(end = 16.dp),
            )
        }
        if (title != null) {
            val titleModifier = if (onTitleClick != null) {
                Modifier.align(Alignment.Center).clickable(onClick = onTitleClick)
            } else {
                Modifier.align(Alignment.Center)
            }
            Text(
                text = title,
                fontSize = 13.sp, letterSpacing = 3.sp, color = Color(0xFF666666),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = titleModifier,
            )
        }
        trailing()
    }
}
