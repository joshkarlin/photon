package app.photon.ui.shared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import app.photon.ui.theme.PhotonColors
import app.photon.ui.theme.lightTextStyle
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Single-line editable field in Copy type over an underline, matching the
 * look of the SDK's (read-only) `LightTextField`.
 */
@Composable
fun LightInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val contentColor = LightThemeTokens.colors.content
    Column(modifier = modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = lightTextStyle(LightTextVariant.Copy).copy(color = contentColor),
            cursorBrush = SolidColor(contentColor),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        LightText(placeholder, variant = LightTextVariant.Copy, color = PhotonColors.Faint)
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(
            modifier = Modifier
                .padding(top = 0.5f.gridUnitsAsDp())
                .fillMaxWidth(0.8f)
                .height(3f.designVerticalPxToDp())
                .background(contentColor),
        )
    }
}
