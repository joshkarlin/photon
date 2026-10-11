package app.photon.ui.shared.components

import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons

/** Top-left Back for secondary screens. */
fun backButton(onBack: () -> Unit): LightBarButton =
    LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack, contentDescription = "Back")

/**
 * Voice note, the same in the inline bar and the full-screen thread's bottom
 * bar: the microphone reads as "record".
 */
internal val VoiceNoteIcon: LightIconConfiguration = LightIcons.MICROPHONE
internal const val VOICE_NOTE = "Voice note"

/** Settings, bottom-left in the chat list's bottom bar, as in LightOS Calendar and Weather. */
fun settingsButton(onSettings: () -> Unit): LightBarButton =
    LightBarButton.LightIcon(icon = LightIcons.SETTINGS, onClick = onSettings, contentDescription = "Settings")

/** New message, bottom-right in the chat list's bottom bar. */
fun newMessageButton(onNewMessage: () -> Unit): LightBarButton =
    LightBarButton.LightIcon(icon = LightIcons.COMPOSE_MESSAGE, onClick = onNewMessage, contentDescription = "New message")
