package app.photon.ui.shared.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import app.photon.ui.theme.PhotonColors
import app.photon.ui.theme.lightTextStyle
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * The inline input under the thread: attach, the text field, and send or
 * voice note. While a voice note records, the bar shows its timer with
 * cancel and send.
 */
@Composable
fun MessageInputBar(
    onSendText: (String) -> Unit,
    media: MediaInput,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }

    val barModifier = modifier
        .fillMaxWidth()
        .height(INPUT_BAR_HEIGHT.gridUnitsAsDp())
        .padding(horizontal = 1f.gridUnitsAsDp())
    val iconSpacing = Arrangement.spacedBy(1f.gridUnitsAsDp())
    val textStyle = lightTextStyle(LightTextVariant.Paragraph)
    val contentColor = LightThemeTokens.colors.content

    if (media.isRecording) {
        Row(
            modifier = barModifier,
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

    Row(
        modifier = barModifier,
        horizontalArrangement = iconSpacing,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (media.canAttach) {
            InputIcon(LightIcons.ADD, "Attach", media::attach)
        }

        // Text input (always visible). Tapping it brings up the keyboard;
        // tapping the thread or turning the dial puts it away (ChatThread).
        // Sending leaves it up to keep typing.
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = textStyle.copy(color = contentColor),
            cursorBrush = SolidColor(contentColor),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (text.isNotBlank()) {
                    onSendText(text.trim())
                    text = ""
                }
            }),
            decorationBox = { inner ->
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (text.isEmpty()) {
                        LightText(
                            text = "Message",
                            variant = LightTextVariant.Paragraph,
                            color = PhotonColors.Faint,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(1f),
        )

        if (text.isNotBlank()) {
            InputIcon(LightIcons.SEND, "Send") {
                onSendText(text.trim())
                text = ""
            }
        } else if (media.canRecord) {
            // Voice note — only when the platform can send audio (SMS can't,
            // so the mic is hidden rather than a dead control).
            InputIcon(LightIcons.MICROPHONE, "Voice note", media::record)
        }
    }
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
