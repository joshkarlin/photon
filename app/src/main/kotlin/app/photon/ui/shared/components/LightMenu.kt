package app.photon.ui.shared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.photon.ui.shared.MenuScrollDialEffect
import app.photon.ui.theme.PhotonColors
import app.photon.ui.theme.PhotonGrid
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * A LightOS-style menu page: top bar (Back on the left when [onBack] is set,
 * centred [title]) over a scrolling column of [MenuRow]s ([PageScrollView])
 * that the scroll dial steps through.
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
        )
        PageScrollView(modifier = Modifier.weight(1f), scrollState = scrollState, content = content)
    }
}

/**
 * A LightOS menu row, as in LightOS Settings: a large left-aligned label in
 * Title Case, no divider, rows [PhotonGrid.MENU_ROW_HEIGHT] apart. An
 * optional value sits beneath the label in the secondary colour
 * ([emphasizeValue] shows it in the content colour, e.g. a live connection).
 * A row that isn't [enabled] recedes and can't be tapped.
 */
@Composable
fun MenuRow(
    label: String,
    value: String? = null,
    emphasizeValue: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhotonGrid.MENU_ROW_HEIGHT.gridUnitsAsDp())
            .let { if (onClick != null) it.lightClickable(enabled = enabled, onClick = onClick) else it },
        verticalArrangement = Arrangement.Center,
    ) {
        LightText(text = label, variant = LightTextVariant.Heading, color = if (enabled) null else PhotonColors.Faint)
        if (value != null) {
            LightText(text = value, variant = LightTextVariant.Detail, lighten = !emphasizeValue)
        }
    }
}
