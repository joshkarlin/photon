package app.photon.ui.shared

import app.photon.data.model.formatTimerDuration
import com.thelightphone.sdk.ui.LightTopBarCenter

/**
 * The thread's top-bar title. While the chat's messages disappear, the
 * SDK's two-line centre puts the timer under the name in Detail
 * ("disappearing · 1 week"), as LightOS writes a state: lower case.
 */
internal fun chatTitle(title: String, disappearingSeconds: Long, onClick: (() -> Unit)?): LightTopBarCenter =
    if (disappearingSeconds > 0) {
        LightTopBarCenter.TwoLineDetail(title, disappearingLine(disappearingSeconds), onClick = onClick)
    } else {
        LightTopBarCenter.Text(title, onClick = onClick)
    }

/** The line under a chat's title while its messages disappear. */
internal fun disappearingLine(seconds: Long): String = "disappearing · ${formatTimerDuration(seconds)}"
