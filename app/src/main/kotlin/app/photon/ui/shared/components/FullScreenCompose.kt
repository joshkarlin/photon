package app.photon.ui.shared.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.photon.data.KeyboardStyle
import app.photon.ui.theme.PhotonGrid
import app.photon.ui.theme.lightTextStyle
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow

// FULL SCREEN compose style, after LightOS Messages: the thread keeps a
// bottom bar of actions, and writing happens in a full-screen editor.

/**
 * The thread's bottom bar: attach on the left and voice note in the middle
 * where the platform supports them, compose on the right. While a voice note
 * records it becomes cancel, timer, send.
 */
@Composable
internal fun ThreadBottomBar(media: MediaInput, onCompose: () -> Unit) {
    val items = if (media.isRecording) {
        listOf(
            LightBarButton.LightIcon(LightIcons.CLOSE, onClick = media::cancelRecording, contentDescription = "Cancel recording"),
            LightBarButton.Text(media.recordingLabel, onClick = null),
            LightBarButton.LightIcon(LightIcons.SEND, onClick = media::sendRecording, contentDescription = "Send voice note"),
        )
    } else {
        // Missing actions stay as empty slots so compose stays on the right.
        listOf(
            LightBarButton.LightIcon(LightIcons.ADD, onClick = media::attach, contentDescription = "Attach")
                .takeIf { media.canAttach },
            LightBarButton.LightIcon(LightIcons.MICROPHONE, onClick = media::record, contentDescription = "Voice note")
                .takeIf { media.canRecord },
            LightBarButton.LightIcon(LightIcons.COMPOSE_MESSAGE, onClick = onCompose, contentDescription = "Compose"),
        )
    }
    LightBottomBar(items = items)
}

/**
 * Full-screen compose: Back and the chat's title on top (with the quoted
 * message under it when replying), the draft as underlined text, and Send.
 * Back (here or the system's) closes it and keeps the draft; blank drafts
 * don't send.
 *
 * With [KeyboardStyle.LIGHT] it is the SDK's [LightTextInputEditor] with the
 * LP3 keyboard; with [KeyboardStyle.SYSTEM] the same layout around a text
 * field that brings up the system keyboard.
 */
@Composable
internal fun ComposeEditor(
    title: String,
    draft: TextFieldState,
    keyboard: KeyboardStyle,
    replyLabel: String?,
    onSend: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val submit = { text: CharSequence -> text.toString().trim().takeIf { it.isNotEmpty() }?.let(onSend) }
    Box(modifier = Modifier.fillMaxSize()) {
        when (keyboard) {
            KeyboardStyle.LIGHT -> LightComposeEditor(draft, onSubmit = { submit(it) }, onBack = onBack)
            KeyboardStyle.SYSTEM -> SystemComposeEditor(draft, onSubmit = { submit(draft.text) })
        }
        LightTopBar(
            leftButton = backButton(onBack),
            center = if (replyLabel == null) {
                LightTopBarCenter.Text(title)
            } else {
                LightTopBarCenter.TwoLineDetail(title, replyLabel)
            },
        )
    }
}

/**
 * The SDK editor. It draws the draft with `BasicText` and types into it
 * through its own keyboard, so nothing opens an input connection and the
 * system keyboard never comes up.
 */
@Composable
private fun LightComposeEditor(draft: TextFieldState, onSubmit: (CharSequence) -> Unit, onBack: () -> Unit) {
    val keyboardOptions = remember { MutableStateFlow(defaultKeyboardOptions()) }
    LightTextInputEditor(
        // The editor's own top bar holds one line; ComposeEditor's, drawn
        // over its empty one, has room for the reply quote.
        title = "",
        showBackButton = false,
        state = draft,
        onSubmit = onSubmit,
        onBack = onBack,
        keyboardOptionsFlow = keyboardOptions,
        submitLabel = "Send",
        submitIcon = LightIcons.SEND,
        // A fresh keyboard per opening (the editor's default), bound to
        // this draft; it starts capitalised when there's nothing yet.
        initialCaps = draft.text.isEmpty(),
    )
}

/**
 * [LightTextInputEditor]'s layout around a real text field: the draft in
 * Heading type over an underline, with Send centred in the bottom bar, which
 * stays above the system keyboard. The field takes focus, and so the
 * keyboard, on open; ComposeEditor draws the top bar over the empty one here.
 */
@Composable
private fun SystemComposeEditor(draft: TextFieldState, onSubmit: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val content = LightThemeTokens.colors.content
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }
    // A Surface, like the SDK editor's, so touches don't reach the thread.
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            Spacer(Modifier.height((PhotonGrid.TOP_BAR_HEIGHT + 1f).gridUnitsAsDp()))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp())
                    .verticalScroll(rememberScrollState()),
            ) {
                BasicTextField(
                    state = draft,
                    textStyle = lightTextStyle(LightTextVariant.Heading).copy(color = content),
                    cursorBrush = SolidColor(content),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(3f.designVerticalPxToDp())
                        .background(content),
                )
            }
            LightBottomBar(
                items = listOf(
                    LightBarButton.LightIcon(LightIcons.SEND, onClick = onSubmit, contentDescription = "Send"),
                ),
            )
        }
    }
}
