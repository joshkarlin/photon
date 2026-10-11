package app.photon.ui.shared.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.photon.data.model.Message
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

private val reactionEmojis = listOf("❤️", "👍", "😂", "😮", "😢", "🙏", "🤙")

/**
 * Full-screen actions for a long-pressed message (react + delete), laid out
 * like an SDK full-screen modal: content centred, close in the bottom bar.
 */
@Composable
fun MessageActions(
    message: Message,
    canReact: Boolean,
    canDelete: Boolean,
    onPick: (String) -> Unit,
    onDelete: (forEveryone: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // "Delete for everyone" only makes sense for our own messages the server
    // has acknowledged — there's a sent message out there to revoke. Anything
    // else (incoming, our own failed/sending rows, call entries) is a
    // local-only removal.
    val forEveryone = message.isFromMe && !isCall(message) &&
        message.status in setOf("sent", "delivered", "read")
    var confirming by remember { mutableStateOf(false) }

    BackHandler(onBack = onDismiss)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background)
            .lightClickable(hapticsEnabled = false, onClick = onDismiss),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.5f.gridUnitsAsDp()),
            ) {
                if (canReact) {
                    reactionEmojis.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp())) {
                            row.forEach { emoji ->
                                LightText(
                                    text = emoji,
                                    variant = LightTextVariant.Heading,
                                    modifier = Modifier.lightClickable { onPick(emoji) },
                                )
                            }
                        }
                    }
                }
                if (canDelete) {
                    LightText(
                        text = when {
                            confirming -> "TAP AGAIN TO CONFIRM"
                            forEveryone -> "DELETE FOR EVERYONE"
                            else -> "DELETE"
                        },
                        variant = LightTextVariant.Copy,
                        modifier = Modifier
                            .lightClickable {
                                // For-everyone is irreversible and visible to
                                // the recipient, so require a second tap.
                                // Local-only deletes happen immediately.
                                if (forEveryone && !confirming) confirming = true
                                else onDelete(forEveryone)
                            }
                            .padding(vertical = 0.5f.gridUnitsAsDp()),
                    )
                }
            }
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(icon = LightIcons.CLOSE, onClick = onDismiss, contentDescription = "Close"),
            ),
        )
    }
}
