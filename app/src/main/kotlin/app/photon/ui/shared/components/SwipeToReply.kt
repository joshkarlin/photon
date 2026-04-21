package app.photon.ui.shared.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Wraps a message row so that dragging right past a threshold triggers `onReply`.
 * Release before the threshold and the row snaps back without firing.
 *
 * A faint "↳" reveal appears under the row while dragging so the gesture is
 * self-documenting. Leftward drags are ignored — scroll gestures stay vertical.
 */
@Composable
fun SwipeToReply(
    enabled: Boolean,
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(modifier = modifier) { content() }
        return
    }

    val density = LocalDensity.current
    val triggerPx = with(density) { 60.dp.toPx() }
    val maxPx = with(density) { 96.dp.toPx() }

    var dragging by remember { mutableStateOf(false) }
    var rawOffset by remember { mutableFloatStateOf(0f) }
    val offset by animateFloatAsState(
        targetValue = if (dragging) rawOffset else 0f,
        label = "swipe-reply-offset",
    )
    var triggered by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        triggered = false
                    },
                    onDragEnd = {
                        if (rawOffset >= triggerPx && !triggered) {
                            triggered = true
                            onReply()
                        }
                        dragging = false
                        rawOffset = 0f
                    },
                    onDragCancel = {
                        dragging = false
                        rawOffset = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        // Only respond to rightward drags — swallowing left drags would
                        // conflict with nothing today but leaves space for future gestures.
                        rawOffset = (rawOffset + dragAmount).coerceIn(0f, maxPx)
                    },
                )
            },
    ) {
        // Reveal icon, shown behind the row while dragging.
        if (offset > 4f) {
            Text(
                text = "↳",
                fontSize = 18.sp,
                color = Color(if (rawOffset >= triggerPx) 0xFFFFFFFF.toInt() else 0xFF444444.toInt()),
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        Box(
            modifier = Modifier.offset { IntOffset(offset.roundToInt(), 0) },
        ) { content() }
    }
}
