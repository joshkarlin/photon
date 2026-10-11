package app.photon.ui.shared.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.photon.ui.theme.PhotonGrid
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Where Photon's scroll bars go. The scroll view spans the screen and its
 * content is inset by [PhotonGrid.MARGIN] on both sides, the same box as a
 * page that doesn't scroll, so the bar is drawn inside the right margin by
 * the screen's edge, as in LightOS Settings.
 *
 * Not the SDK's default `Outside`: its lazy list reserves the bar's width
 * twice (end padding on the list as well as the bar beside it), which left
 * the chat list's times three units short of the bar.
 */
internal val PageScrollBar = LightScrollBarPosition.Inside

/**
 * A page's scrolling content: the SDK's [LightScrollView] across the full
 * width, with [content] inset by the page margin ([PageScrollBar]).
 */
@Composable
fun PageScrollView(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    LightScrollView(
        modifier = modifier.fillMaxWidth(),
        scrollBarPosition = PageScrollBar,
        scrollState = scrollState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PhotonGrid.MARGIN.gridUnitsAsDp()),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}
