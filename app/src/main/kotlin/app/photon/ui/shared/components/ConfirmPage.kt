package app.photon.ui.shared.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import app.photon.ui.theme.PhotonGrid
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * A LightOS page that says what an action will do before it's done, for one
 * that can't be undone: Back and [title] in the top bar, [text] centred, and
 * the action itself, [action] (e.g. "DELETE"), in the bottom bar. Back, the
 * top bar's or the system's, cancels.
 */
@Composable
fun ConfirmPage(
    title: String,
    text: String,
    action: String,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(leftButton = backButton(onBack), center = LightTopBarCenter.Text(title))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = PhotonGrid.MARGIN.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(text = text, variant = LightTextVariant.Copy, align = TextAlign.Center)
        }
        LightBottomBar(items = listOf(LightBarButton.Text(text = action, onClick = onConfirm)))
    }
}
