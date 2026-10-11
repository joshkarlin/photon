package app.photon.ui.shared.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * The attachment sheet, as LightOS Messages draws it over a thread: the
 * thread's title in the top bar, a row of unlabelled icons in the lower part
 * of the page, and a chevron at the bottom that closes it. The icons are what
 * [media] can do: Camera (when an app can take a photo), Photos and Files.
 *
 * Each hands over to the camera app or a picker and closes the sheet; what
 * comes back is sent to the thread. Back, here or the system's, returns to
 * the thread.
 */
@Composable
internal fun AttachSheet(title: String, media: MediaInput, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    fun choose(action: () -> Unit) {
        action()
        onClose()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background)
            // A page over the thread: touches stop here.
            .pointerInput(Unit) {},
    ) {
        LightTopBar(leftButton = backButton(onClose), center = LightTopBarCenter.Text(title))

        // LightOS puts the row about three quarters of the way down the page's body.
        Spacer(Modifier.weight(3f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ATTACH_ICON_GAP_UNITS.gridUnitsAsDp(), Alignment.CenterHorizontally),
        ) {
            if (media.canTakePhoto) AttachIcon(LightIcons.CAMERA, "Camera") { choose(media::takePhoto) }
            if (media.canAttach) {
                AttachIcon(LightIcons.MEDIA, "Photos") { choose(media::pickPhoto) }
                AttachIcon(LightIcons.LARGE_LIST, "Files") { choose(media::pickFile) }
            }
        }
        Spacer(Modifier.weight(1f))

        LightBottomBar(items = listOf(LightBarButton.LightIcon(LightIcons.DOWN, onClick = onClose, contentDescription = "Close")))
    }
}

@Composable
private fun AttachIcon(icon: LightIconConfiguration, description: String, onClick: () -> Unit) {
    LightIcon(
        icon = icon,
        size = ATTACH_ICON_UNITS,
        contentDescription = description,
        modifier = Modifier.lightClickable(onClick = onClick),
    )
}

/** The icons' size: the SDK bar buttons'. */
private const val ATTACH_ICON_UNITS = 2f

/** Between icons, as LightOS spaces them: 220px apart, centre to centre. */
private const val ATTACH_ICON_GAP_UNITS = 3.5f
