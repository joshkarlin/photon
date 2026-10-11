package app.photon.ui.shared

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import app.photon.data.ScrollDial
import app.photon.ui.theme.PhotonGrid
import app.photon.ui.theme.lightTextStyle
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** One dial detent: [direction] is +1 (clockwise, down) or -1 (counter-clockwise, up). */
data class ScrollDialEvent(val direction: Int, val setting: ScrollDial)

/**
 * LP3 scroll dial support. The Pixart pat9126ja rotary encoder emits
 * WHEEL_CW (318, clockwise) and WHEEL_CCW (317, counter-clockwise),
 * one event per detent.
 *
 * Events are captured at the Activity level (dispatchKeyEvent), which only
 * consumes them while the setting isn't [ScrollDial.OFF], and broadcast via
 * [ScrollDialState] to any screen that calls [ScrollDialEffect].
 */
object ScrollDialState {
    private val _events = MutableSharedFlow<ScrollDialEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ScrollDialEvent> = _events

    fun emit(event: ScrollDialEvent) {
        _events.tryEmit(event)
    }
}

/** Observe scroll dial events. */
@Composable
fun ScrollDialEffect(onScroll: suspend (ScrollDialEvent) -> Unit) {
    val handler by rememberUpdatedState(onScroll)
    LaunchedEffect(Unit) {
        ScrollDialState.events.collect { handler(it) }
    }
}

/** First visible index after one detent on a list stepping [ScrollDial.listItems] rows. */
fun listDialTarget(firstVisibleIndex: Int, event: ScrollDialEvent): Int =
    (firstVisibleIndex + event.direction * event.setting.listItems).coerceAtLeast(0)

/** Steps a list by whole rows per detent. */
@Composable
fun ListScrollDialEffect(listState: LazyListState) {
    ScrollDialEffect { event ->
        listState.scrollToItem(listDialTarget(listState.firstVisibleItemIndex, event))
    }
}

/** Steps a scrolling menu by one menu row per [ScrollDial.listItems]. */
@Composable
fun MenuScrollDialEffect(scrollState: ScrollState) {
    val rowPx = with(LocalDensity.current) { PhotonGrid.MENU_ROW_HEIGHT.gridUnitsAsDp().toPx() }
    ScrollDialEffect { event ->
        scrollState.scrollBy(event.direction * event.setting.listItems * rowPx)
    }
}

/**
 * Scrolls a message list by [ScrollDial.chatLines] message-body lines per
 * detent. The thread uses `reverseLayout`, so clockwise (down, towards newer
 * messages) is a negative scroll. [onTurn] runs first on every detent;
 * neither runs while not [enabled].
 */
@Composable
fun ChatScrollDialEffect(listState: LazyListState, enabled: Boolean = true, onTurn: () -> Unit = {}) {
    val linePx = with(LocalDensity.current) {
        lightTextStyle(LightTextVariant.Paragraph).lineHeight.toPx()
    }
    ScrollDialEffect { event ->
        if (!enabled) return@ScrollDialEffect
        onTurn()
        listState.scrollBy(-event.direction * event.setting.chatLines * linePx)
    }
}
