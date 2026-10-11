package app.photon.ui.shared.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.photon.data.model.Message
import app.photon.data.model.MessageStatus
import app.photon.data.names.DisplayNames
import app.photon.ui.theme.PhotonColors
import app.photon.ui.theme.PhotonGrid
import com.thelightphone.sdk.ui.LightHapticFeedback
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Message rows are assembled from small pieces — timestamp, sender label,
// body, meta (status + reactions), reply quote — so the arrangement can change
// without touching the pieces. Typography follows the LP3 thread: message text
// in Paragraph, everything around it in Detail.

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayTimeFormat = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())

/** A gap longer than this between two messages starts a new burst. */
internal const val BURST_GAP_SECONDS = 15 * 60

/** "14:02" for today, "May 11 14:02" for earlier days, as the LP3 thread shows it. */
internal fun formatMessageTime(epochSeconds: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val date = Date(epochSeconds * 1000)
    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val then = Calendar.getInstance().apply { time = date }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) timeFormat.format(date) else dayTimeFormat.format(date)
}

/**
 * Whether [msg] begins a new burst: a run of messages from one sender with no
 * gap over [BURST_GAP_SECONDS]. [previous] is the message shown just above it
 * (the next older one). Call rows always stand alone.
 */
internal fun startsBurst(msg: Message, previous: Message?): Boolean = when {
    previous == null -> true
    isCall(msg) || isCall(previous) -> true
    msg.isFromMe != previous.isFromMe -> true
    !msg.isFromMe && msg.senderJid != previous.senderJid -> true
    else -> msg.timestamp - previous.timestamp > BURST_GAP_SECONDS
}

internal fun formatContent(msg: Message): String {
    val body = msg.textBody?.takeIf { it.isNotBlank() }
    return when (msg.contentType) {
        "text" -> body ?: "[ deleted ]"
        "image" -> if (body != null) "[ image ] $body" else "[ image ]"
        "video" -> if (body != null) "[ video ] $body" else "[ video ]"
        "audio" -> "[ audio ]"
        "document" -> "[ ${body ?: "document"} ]"
        "sticker" -> "[ sticker ]"
        "location" -> "[ location ]"
        "contact" -> "[ contact: ${body ?: ""} ]"
        "call" -> body ?: "Call"
        // Unknown types: the platforms store a readable placeholder (e.g.
        // "[ unsupported message ]") in the body when they have one.
        else -> body ?: "[ ${msg.contentType} ]"
    }
}

/**
 * Bodies with no message content of their own — a deleted message or a
 * bracketed placeholder such as "[ unsupported message ]" — which recede.
 */
internal fun isPlaceholder(msg: Message): Boolean {
    val body = msg.textBody?.trim()
    val isBracketed = body != null && body.startsWith("[") && body.endsWith("]")
    return when (msg.contentType) {
        "text" -> body.isNullOrEmpty() || isBracketed
        "image", "video", "audio", "document", "sticker", "location", "contact", "call" -> false
        else -> body.isNullOrEmpty() || isBracketed
    }
}

internal fun isCall(msg: Message): Boolean = msg.contentType == "call"

internal fun isMedia(msg: Message): Boolean =
    msg.contentType in listOf("image", "video", "audio", "document", "sticker")

private fun formatReactions(msg: Message): String =
    msg.reactions.groupBy { it.emoji }.entries.joinToString(" ") { (emoji, rs) ->
        if (rs.size > 1) "$emoji${rs.size}" else emoji
    }

/**
 * One-line "↳ who: preview" label for a quoted/replied-to message. Shared by
 * the per-message reply quote lines, the reply chip above the input and the
 * full-screen editor.
 *
 * @param quotedSenderName who sent the quoted message, as DisplayNames.sender
 *   resolves it; null in a DM, where the title already names them
 *   ("↳ preview"). A quote of your own message always says "You".
 */
fun quoteLabel(quoted: Message, quotedSenderName: String?): String {
    val who = if (quoted.isFromMe) DisplayNames.YOU else quotedSenderName?.takeIf { it.isNotBlank() }
    val preview = when {
        !quoted.textBody.isNullOrBlank() -> quoted.textBody.replace('\n', ' ')
        else -> "[ ${quoted.contentType} ]"
    }.take(60)
    return if (who == null) "↳ $preview" else "↳ $who: $preview"
}

/**
 * Tap and long-press without a press indication, like `lightClickable`. The
 * haptic fires on long-press only: message rows are touched constantly while
 * scrolling, so a finger-down buzz would be noise.
 */
internal fun Modifier.lightCombinedClickable(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier = composed {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    combinedClickable(
        interactionSource = null,
        indication = null,
        onClick = onClick,
        onLongClick = {
            if (hapticsEnabled) LightHapticFeedback.click(context)
            onLongClick()
        },
    )
}

// ─── Pieces ─────────────────────────────────────────────────────

/** Time (and date, if not today) above a message, on the message's side. */
@Composable
fun MessageTimestamp(msg: Message, modifier: Modifier = Modifier, lighten: Boolean = false) {
    LightText(
        text = formatMessageTime(msg.timestamp),
        variant = LightTextVariant.Detail,
        lighten = lighten,
        maxLines = 1,
        modifier = modifier,
    )
}

/** A group sender's name: one line, ellipsized. */
@Composable
fun SenderLabel(name: String, modifier: Modifier = Modifier) {
    LightText(
        text = name,
        variant = LightTextVariant.Detail,
        lighten = true,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The message text itself. Placeholders recede. */
@Composable
fun MessageBody(msg: Message, modifier: Modifier = Modifier, alignEnd: Boolean = false) {
    LightText(
        text = formatContent(msg),
        variant = LightTextVariant.Paragraph,
        color = if (isPlaceholder(msg)) PhotonColors.Faint else null,
        align = if (alignEnd) TextAlign.End else TextAlign.Start,
        modifier = modifier,
    )
}

/**
 * The line under a message: reactions, then delivery status. Sending and
 * failed always show (a failed send must be noticed and retried); sent /
 * delivered / read only when the user has revealed the message's details.
 */
@Composable
fun MessageMeta(
    msg: Message,
    revealed: Boolean,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val reactions = formatReactions(msg)
    val status = if (msg.isFromMe) {
        when (msg.status) {
            MessageStatus.SENDING -> "sending…"
            MessageStatus.FAILED -> "failed · tap to retry"
            MessageStatus.SENT, MessageStatus.DELIVERED, MessageStatus.READ ->
                msg.status.name.lowercase().takeIf { revealed }
            else -> null
        }
    } else null
    if (reactions.isEmpty() && status == null) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(0.5f.gridUnitsAsDp()),
    ) {
        if (reactions.isNotEmpty()) {
            LightText(text = reactions, variant = LightTextVariant.Detail)
        }
        if (status != null) {
            val failed = msg.status == MessageStatus.FAILED
            LightText(
                text = status,
                variant = LightTextVariant.Detail,
                lighten = !failed,
                modifier = if (failed && onRetry != null) Modifier.lightClickable(onClick = onRetry) else Modifier,
            )
        }
    }
}

/** Compact one-line quote shown above a message that is a reply. */
@Composable
fun ReplyQuoteLine(
    quoted: Message,
    quotedSenderName: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    LightText(
        text = quoteLabel(quoted, quotedSenderName),
        variant = LightTextVariant.Detail,
        lighten = true,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.let { if (onClick != null) it.lightClickable(onClick = onClick) else it },
    )
}

// ─── Rows ───────────────────────────────────────────────────────

/**
 * A call log entry: one centred line, e.g. "Missed voice call · 14:02". No
 * reply or reaction; long-press offers delete only.
 */
@Composable
fun CallRow(msg: Message, onLongPress: () -> Unit = {}) {
    LightText(
        text = "${formatContent(msg)} · ${formatMessageTime(msg.timestamp)}",
        variant = LightTextVariant.Detail,
        lighten = true,
        align = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .lightCombinedClickable(onClick = {}, onLongClick = onLongPress),
    )
}

/**
 * One message in the thread, laid out like the LP3 thread: no bubbles;
 * received messages on the left, sent on the right. The first message of a
 * burst carries its timestamp above it; tapping any other message reveals its
 * timestamp in the same place (and delivery status below).
 *
 * Groups pass [senderName], which puts the sender before the timestamp on
 * incoming bursts, as the LP2 group thread does ("Name  12:36"). DMs pass null.
 */
@Composable
fun MessageRow(
    msg: Message,
    startsBurst: Boolean,
    onMediaTap: () -> Unit,
    onLongPress: () -> Unit = {},
    senderName: String? = null,
    quotedSenderName: String? = null,
    onReplyPreviewTap: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    var revealed by remember { mutableStateOf(false) }
    val fromMe = msg.isFromMe
    val showName = senderName != null && !fromMe && startsBurst

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightCombinedClickable(
                onClick = { if (isMedia(msg)) onMediaTap() else revealed = !revealed },
                onLongClick = onLongPress,
            ),
        horizontalAlignment = if (fromMe) Alignment.End else Alignment.Start,
    ) {
        if (startsBurst || revealed) {
            Row(horizontalArrangement = Arrangement.spacedBy(0.75f.gridUnitsAsDp())) {
                if (showName) SenderLabel(senderName.orEmpty())
                MessageTimestamp(msg, lighten = senderName != null)
            }
        }
        msg.replyToPreview?.let { quoted ->
            ReplyQuoteLine(
                quoted = quoted,
                quotedSenderName = quotedSenderName,
                onClick = onReplyPreviewTap,
                modifier = Modifier.widthIn(max = PhotonGrid.MESSAGE_MAX_WIDTH.gridUnitsAsDp()),
            )
        }
        MessageBody(
            msg = msg,
            alignEnd = fromMe,
            modifier = Modifier.widthIn(max = PhotonGrid.MESSAGE_MAX_WIDTH.gridUnitsAsDp()),
        )
        MessageMeta(msg, revealed = revealed, onRetry = onRetry)
    }
}
