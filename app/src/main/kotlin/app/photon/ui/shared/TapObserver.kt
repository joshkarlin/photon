package app.photon.ui.shared

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Calls [onTap] when this element or anything inside it is tapped, without
 * consuming the tap, so children's own click handlers still run. Drags
 * (scrolling, swipe-to-reply) and long-presses are not taps.
 *
 * Pass a remembered [onTap]: a new lambda restarts the gesture detector.
 */
internal fun Modifier.observeTaps(onTap: () -> Unit): Modifier = pointerInput(onTap) {
    awaitEachGesture {
        // The Initial pass sees each event before the children handle it.
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val tapped = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var result: Boolean? = null
            while (result == null) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes
                    .firstOrNull { it.id == down.id }
                result = when {
                    change == null -> false
                    !change.pressed -> true
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop -> false
                    else -> null
                }
            }
            result
        } == true
        if (tapped) onTap()
    }
}
