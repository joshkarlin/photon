package app.photon.ui.shared.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
import com.thelightphone.sdk.ui.LightHapticFeedback
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow

// The LP3 keyboard for the inline input (Settings › Chat › Keyboard: LIGHT).
// The SDK embeds it only in its full-screen LightTextInputEditor; here it is
// docked under the inline bar and types into the same draft.

/**
 * An LP3 keyboard that types into [draft]. Return calls [onReturn]. Starts in
 * capitals; call `setCapsMode(true)` to start the next message the same way.
 */
@Composable
internal fun rememberLightKeyboard(draft: TextFieldState, onReturn: () -> Unit): Lp3KeyboardViewModel<*> {
    val currentOnReturn by rememberUpdatedState(onReturn)
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val callback = remember(draft) {
        DraftKeyboardCallback(
            state = draft,
            onReturn = { currentOnReturn() },
            onHaptic = { if (hapticsEnabled) LightHapticFeedback.click(context) },
        )
    }
    // Keyed by the callback: a keyboard view model outlives this composition
    // (it's kept by the screen's ViewModelStore), and it must never type into
    // a draft, or call an onReturn, from an earlier one.
    return viewModel<EnQwertyLp3KeyboardViewModel<*>>(
        key = "photon-light-keyboard-${System.identityHashCode(callback)}",
        factory = remember(callback) { keyboardFactory(callback) },
    )
}

/**
 * The LP3 keyboard, docked in the layout. It can't take focus, so the text
 * field it types into keeps its cursor and selection (and a hardware
 * keyboard, if any, types into the field directly).
 */
@Composable
internal fun DockedLightKeyboard(keyboard: Lp3KeyboardViewModel<*>, modifier: Modifier = Modifier) {
    Box(modifier = modifier.focusProperties { canFocus = false }) {
        LightEmbeddedLp3Keyboard(viewModel = keyboard)
    }
}

/**
 * Keeps the system keyboard away from text fields in [content]: their input
 * sessions are never handed to the platform, so no input connection opens and
 * no IME can show. The fields still focus, show a cursor and take edits made
 * to their state.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun WithoutSystemKeyboard(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(interceptor = { _, _ -> awaitCancellation() }, content = content)
}

// Adapted from the Light SDK v0.2.0 (MIT License, Copyright (c) 2026 The
// Light Phone): `factory` in sdk/ui/src/main/kotlin/com/thelightphone/sdk/ui/
// LightTextInputEditor.kt, which is private there.
private fun keyboardFactory(callback: Lp3RepeatableKeyboardCallback): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            EnQwertyLp3KeyboardViewModel<Unit>(
                callback,
                keyboardOptionsFlow = MutableStateFlow(defaultKeyboardOptions()),
                optionsForLayout = { LayoutOptions(!it.isRootLayout) },
            ).apply { setCapsMode(true) } as T
    }

/**
 * Applies LP3 key presses to [state] at its selection. Return calls
 * [onReturn] rather than adding a newline: the inline bar sends on Return, as
 * the system keyboard's Send action does.
 *
 * Adapted from the Light SDK v0.2.0 (MIT License, Copyright (c) 2026 The
 * Light Phone): `TextInputKeyboardCallback` in sdk/ui/src/main/kotlin/com/
 * thelightphone/sdk/ui/keyboard/TextInputKeyboardCallback.kt, which is
 * internal there. Its single-line behaviour, trimmed.
 */
internal class DraftKeyboardCallback(
    private val state: TextFieldState,
    private val onReturn: () -> Unit,
    private val onHaptic: () -> Unit,
) : Lp3RepeatableKeyboardCallback {

    override fun onKeyPressed(code: Int) = onHaptic()

    override fun onSpecialKeyPressed(key: SpecialKey) {
        onHaptic()
        if (key == SpecialKey.Space) insert(" ")
    }

    override fun onKeyReleased(code: Int) = insert(String(Character.toChars(code)))

    override fun onSpecialKeyReleased(key: SpecialKey) {
        when (key) {
            SpecialKey.Backspace -> deleteBeforeCursor(charCountBefore(state.selection.min))
            SpecialKey.Return -> onReturn()
            else -> Unit
        }
    }

    override fun onKeyLongPressed(code: Int) = Unit

    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        if (key == SpecialKey.Backspace) {
            val before = state.text.subSequence(0, state.selection.min)
            val trimmed = before.trimEnd()
            val lastSpace = trimmed.indexOfLast { it.isWhitespace() }
            deleteBeforeCursor(before.length - if (lastSpace >= 0) lastSpace + 1 else 0)
        }
    }

    override fun onKeyRepeated(code: Int) = insert(String(Character.toChars(code)))

    override fun onSpecialKeyRepeated(specialKey: SpecialKey) {
        if (specialKey == SpecialKey.Space) insert(" ")
    }

    override fun onSubmitWord(word: CharSequence) = insert(word.toString())

    private fun insert(text: String) {
        state.edit {
            val start = selection.min
            replace(start, selection.max, text)
            selection = TextRange(start + text.length)
        }
    }

    /** One character, or both halves of a surrogate pair (an emoji). */
    private fun charCountBefore(cursor: Int): Int = when {
        cursor == 0 -> 0
        Character.isLowSurrogate(state.text[cursor - 1]) -> 2
        else -> 1
    }

    private fun deleteBeforeCursor(count: Int) {
        if (count <= 0) return
        state.edit {
            val end = selection.min
            if (end == 0) return@edit
            val start = (end - count).coerceAtLeast(0)
            delete(start, end)
            selection = TextRange(start)
        }
    }
}
