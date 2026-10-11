package app.photon.ui.shared.components

import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons

/** Top-left Back for secondary screens. */
fun backButton(onBack: () -> Unit): LightBarButton =
    LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack, contentDescription = "Back")

/** Top-right Settings for the chat lists. */
fun settingsButton(onSettings: () -> Unit): LightBarButton =
    LightBarButton.LightIcon(icon = LightIcons.SETTINGS, onClick = onSettings, contentDescription = "Settings")
