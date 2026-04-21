package app.photon.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * LP3 scroll dial support. The Pixart pat9126ja rotary encoder emits
 * WHEEL_CW (318, clockwise) and WHEEL_CCW (317, counter-clockwise),
 * one event per detent.
 *
 * Events are captured at the Activity level (dispatchKeyEvent) and
 * broadcast via [ScrollDialState] to any screen that calls [ScrollDialEffect].
 */
object ScrollDialState {
    private val _events = MutableSharedFlow<Float>(extraBufferCapacity = 16)
    val events: SharedFlow<Float> = _events

    /** Emit +1f for CW (down) or -1f for CCW (up). */
    fun emit(delta: Float) {
        _events.tryEmit(delta)
    }
}

/**
 * Observe scroll dial events. [onScroll] receives +1f (CW/down) or -1f (CCW/up).
 */
@Composable
fun ScrollDialEffect(onScroll: suspend (Float) -> Unit) {
    LaunchedEffect(Unit) {
        ScrollDialState.events.collect { direction ->
            onScroll(direction)
        }
    }
}
