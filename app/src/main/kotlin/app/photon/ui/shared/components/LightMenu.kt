package app.photon.ui.shared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.photon.ui.shared.MenuScrollDialEffect
import app.photon.ui.theme.PhotonGrid
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * A LightOS-style menu page: top bar (Back on the left when [onBack] is set,
 * centred [title]) over a scrolling column of [MenuRow]s that the scroll dial
 * steps through.
 */
@Composable
fun MenuScreen(
    title: String?,
    onBack: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    MenuScrollDialEffect(scrollState)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(
            leftButton = onBack?.let(::backButton),
            center = title?.let { LightTopBarCenter.Text(it) },
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = PhotonGrid.MENU_MARGIN.gridUnitsAsDp()),
            scrollState = scrollState,
            content = content,
        )
    }
}

/**
 * A LightOS menu row: large left-aligned label, no divider, with an optional
 * value beneath it in the secondary colour ([emphasizeValue] shows it in the
 * content colour, e.g. a live connection).
 */
@Composable
fun MenuRow(
    label: String,
    value: String? = null,
    emphasizeValue: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.lightClickable(onClick = onClick) else it }
            .padding(vertical = PhotonGrid.MENU_ROW_PADDING.gridUnitsAsDp()),
    ) {
        LightText(text = label, variant = LightTextVariant.Copy)
        if (value != null) {
            LightText(text = value, variant = LightTextVariant.Paragraph, lighten = !emphasizeValue)
        }
    }
}
