package app.photon.ui.shared.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import app.photon.data.KeyboardStyle
import app.photon.ui.theme.PhotonColors
import app.photon.ui.theme.lightTextStyle
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3KeyboardViewModel
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * The inline input under the thread: attach, the [draft] field, and send or
 * voice note. While a voice note records, the bar shows its timer with
 * cancel and send.
 *
 * Tapping the field brings up the keyboard: the system one, or with
 * [KeyboardStyle.LIGHT] the LP3 keyboard docked under the bar. Clearing focus
 * (ChatThread does on a tap in the thread or a dial turn) puts either away.
 * Sending leaves it up to keep typing; so does Return on the LP3 keyboard,
 * which sends.
 */
@Composable
fun MessageInputBar(
    draft: TextFieldState,
    keyboard: KeyboardStyle,
    onSendText: (String) -> Unit,
    media: MediaInput,
    modifier: Modifier = Modifier,
) {
    var lightKeyboard: Lp3KeyboardViewModel<*>? = null
    // Each message starts with a capital on the LP3 keyboard, as the first does.
    val send = {
        if (sendDraft(draft, onSendText)) lightKeyboard?.setCapsMode(true)
    }
    if (keyboard == KeyboardStyle.LIGHT) lightKeyboard = rememberLightKeyboard(draft, onReturn = send)
    var fieldFocused by remember { mutableStateOf(false) }
    // A voice note replaces the field, which may not report losing focus.
    LaunchedEffect(media.isRecording) { if (media.isRecording) fieldFocused = false }
    // As with the system keyboard, Back puts the LP3 keyboard away first.
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = lightKeyboard != null && fieldFocused) { focusManager.clearFocus() }

    val barModifier = Modifier
        .fillMaxWidth()
        .height(INPUT_BAR_HEIGHT.gridUnitsAsDp())
        .padding(horizontal = 1f.gridUnitsAsDp())
    val iconSpacing = Arrangement.spacedBy(1f.gridUnitsAsDp())

    if (media.isRecording) {
        Row(
            modifier = modifier.then(barModifier),
            horizontalArrangement = iconSpacing,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = media.recordingLabel,
                variant = LightTextVariant.Paragraph,
                modifier = Modifier.weight(1f),
            )
            InputIcon(LightIcons.CLOSE, "Cancel recording", media::cancelRecording)
            InputIcon(LightIcons.SEND, "Send voice note", media::sendRecording)
        }
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = barModifier,
            horizontalArrangement = iconSpacing,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (media.canAttach) {
                InputIcon(LightIcons.ADD, "Attach", media::attach)
            }

            val field = @Composable {
                DraftField(
                    draft = draft,
                    keyboard = keyboard,
                    onSend = send,
                    modifier = Modifier.onFocusChanged { fieldFocused = it.isFocused },
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                if (keyboard == KeyboardStyle.LIGHT) WithoutSystemKeyboard(field) else field()
            }

            if (draft.text.isNotBlank()) {
                InputIcon(LightIcons.SEND, "Send", send)
            } else if (media.canRecord) {
                // Voice note — only when the platform can send audio (SMS can't,
                // so the mic is hidden rather than a dead control).
                InputIcon(LightIcons.MICROPHONE, "Voice note", media::record)
            }
        }

        val docked = lightKeyboard
        if (docked != null && fieldFocused) DockedLightKeyboard(docked)
    }
}

@Composable
private fun DraftField(
    draft: TextFieldState,
    keyboard: KeyboardStyle,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = LightThemeTokens.colors.content
    BasicTextField(
        state = draft,
        textStyle = lightTextStyle(LightTextVariant.Paragraph).copy(color = contentColor),
        cursorBrush = SolidColor(contentColor),
        keyboardOptions = when (keyboard) {
            KeyboardStyle.SYSTEM -> KeyboardOptions(imeAction = ImeAction.Send)
            KeyboardStyle.LIGHT -> KeyboardOptions(showKeyboardOnFocus = false)
        },
        onKeyboardAction = { onSend() },
        decorator = { inner ->
            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxWidth()) {
                if (draft.text.isEmpty()) {
                    LightText(
                        text = "Message",
                        variant = LightTextVariant.Paragraph,
                        color = PhotonColors.Faint,
                    )
                }
                inner()
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/** Sends and clears the draft, unless it's blank. Returns whether it sent. */
private fun sendDraft(draft: TextFieldState, onSendText: (String) -> Unit): Boolean {
    val text = draft.text.trim()
    if (text.isEmpty()) return false
    onSendText(text.toString())
    draft.clearText()
    return true
}

private const val INPUT_BAR_HEIGHT = 3.5f

@Composable
private fun InputIcon(icon: LightIconConfiguration, description: String, onClick: () -> Unit) {
    LightIcon(
        icon = icon,
        contentDescription = description,
        modifier = Modifier.lightClickable(onClick = onClick),
    )
}
