package app.photon.ui.shared.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.defaultKeyboardOptions
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
 * Full-screen compose on the SDK's [LightTextInputEditor]: Back and the
 * chat's title on top (with the quoted message under it when replying), the
 * draft as underlined text, the embedded LP3 keyboard, and Send in the bottom
 * bar. Blank drafts don't send.
 *
 * The editor draws the draft with `BasicText` and types into [draft] through
 * its own keyboard, so nothing opens an input connection and the system
 * keyboard never comes up.
 */
@Composable
internal fun ComposeEditor(
    title: String,
    draft: TextFieldState,
    replyLabel: String?,
    onSend: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val keyboardOptions = remember { MutableStateFlow(defaultKeyboardOptions()) }
    Box(modifier = Modifier.fillMaxSize()) {
        LightTextInputEditor(
            // The editor's own top bar holds one line; ours, drawn over its
            // empty one, has room for the reply quote.
            title = "",
            showBackButton = false,
            state = draft,
            onSubmit = { text -> text.toString().trim().takeIf { it.isNotEmpty() }?.let(onSend) },
            onBack = onBack,
            keyboardOptionsFlow = keyboardOptions,
            submitLabel = "Send",
            submitIcon = LightIcons.SEND,
            // A fresh keyboard per opening (the editor's default), bound to
            // this draft; it starts capitalised when there's nothing yet.
            initialCaps = draft.text.isEmpty(),
        )
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
