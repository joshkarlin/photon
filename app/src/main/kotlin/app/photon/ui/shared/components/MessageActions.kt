package app.photon.ui.shared.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import app.photon.data.model.Message
import app.photon.data.model.MessageStatus
import app.photon.ui.theme.PhotonGrid
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** The emoji a message can be reacted with. */
internal val REACTION_EMOJIS = listOf("❤️", "👍", "😂", "😮", "😢", "🙏", "🤙")

/** What a long-pressed message's menu offers, in the order it lists them. */
internal enum class MessageAction(val label: String) {
    REPLY("Reply"),
    REACT("React"),
    RETRY("Retry"),

    /** Removes the message from Photon, on this phone only. */
    DELETE("Delete"),
    DELETE_FOR_EVERYONE("Delete for Everyone"),
}

/** What the chat lets the user do to its messages, from its platform's capabilities. */
internal data class MessageActionSupport(
    val reply: Boolean = false,
    val react: Boolean = false,
    val retry: Boolean = false,
    val delete: Boolean = false,
    val deleteForEveryone: Boolean = false,
)

/**
 * The actions [msg] offers. Reply, React and Delete for Everyone need a
 * message the others have: not a call row (Photon's own record), not one of
 * the user's still unsent, and not one already deleted. Reactions go on
 * other people's messages only, on every platform.
 */
internal fun messageActions(msg: Message, support: MessageActionSupport): List<MessageAction> = buildList {
    val call = isCall(msg)
    val delivered = !call && !msg.status.isUnsent && msg.status != MessageStatus.DELETED
    if (support.reply && delivered) add(MessageAction.REPLY)
    if (support.react && delivered && !msg.isFromMe) add(MessageAction.REACT)
    if (support.retry && !call && msg.isFromMe && msg.status == MessageStatus.FAILED) add(MessageAction.RETRY)
    if (support.delete) add(MessageAction.DELETE)
    if (support.delete && support.deleteForEveryone && delivered && msg.isFromMe) add(MessageAction.DELETE_FOR_EVERYONE)
}

private enum class ActionPage { MENU, REACT, CONFIRM_DELETE }

/**
 * The pages behind a long-pressed message, over the thread: its [actions]
 * as a LightOS menu titled with the message. React opens a page of emoji,
 * and Delete for Everyone a page that says what it does before it does it.
 * Back on any of them, or finishing an action, returns to the thread
 * ([onDismiss]).
 */
@Composable
internal fun MessageActions(
    message: Message,
    actions: List<MessageAction>,
    onReply: () -> Unit,
    onReact: (emoji: String) -> Unit,
    onRetry: () -> Unit,
    onDelete: (forEveryone: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var page by remember(message.id) { mutableStateOf(ActionPage.MENU) }
    fun finish(action: () -> Unit) {
        action()
        onDismiss()
    }

    BackHandler(onBack = onDismiss)
    // A page over the thread: touches stop here, not on the messages beneath.
    Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) {}) {
        when (page) {
            ActionPage.MENU -> MenuScreen(title = formatContent(message).replace('\n', ' '), onBack = onDismiss) {
                actions.forEach { action ->
                    MenuRow(action.label) {
                        when (action) {
                            MessageAction.REPLY -> finish(onReply)
                            MessageAction.REACT -> page = ActionPage.REACT
                            MessageAction.RETRY -> finish(onRetry)
                            MessageAction.DELETE -> finish { onDelete(false) }
                            MessageAction.DELETE_FOR_EVERYONE -> page = ActionPage.CONFIRM_DELETE
                        }
                    }
                }
            }
            ActionPage.REACT -> ReactionPicker(onPick = { emoji -> finish { onReact(emoji) } }, onBack = onDismiss)
            ActionPage.CONFIRM_DELETE -> ConfirmDeleteForEveryone(onConfirm = { finish { onDelete(true) } }, onBack = onDismiss)
        }
    }
}

/** The emoji to react with, each a large tap target, centred under the top bar. */
@Composable
private fun ReactionPicker(onPick: (String) -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(leftButton = backButton(onBack), center = LightTopBarCenter.Text(MessageAction.REACT.label))
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                REACTION_EMOJIS.chunked(REACTIONS_PER_ROW).forEach { row ->
                    Row {
                        row.forEach { emoji ->
                            Box(
                                modifier = Modifier
                                    .size(REACTION_CELL_UNITS.gridUnitsAsDp())
                                    .lightClickable { onPick(emoji) },
                                contentAlignment = Alignment.Center,
                            ) {
                                LightText(emoji, variant = LightTextVariant.Subtitle)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Delete for Everyone can't be undone, and the others see that a message
 * was deleted, so this says so, with the delete itself in the bottom bar.
 */
@Composable
private fun ConfirmDeleteForEveryone(onConfirm: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(
            leftButton = backButton(onBack),
            center = LightTopBarCenter.Text(MessageAction.DELETE_FOR_EVERYONE.label),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = PhotonGrid.MARGIN.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = DELETE_FOR_EVERYONE_WARNING,
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
            )
        }
        LightBottomBar(items = listOf(LightBarButton.Text(text = "DELETE", onClick = onConfirm)))
    }
}

internal const val DELETE_FOR_EVERYONE_WARNING =
    "Delete this message for everyone in the chat? They'll see that it was deleted."

private const val REACTIONS_PER_ROW = 4

/** Side of each emoji's tap target. */
private const val REACTION_CELL_UNITS = 5f
