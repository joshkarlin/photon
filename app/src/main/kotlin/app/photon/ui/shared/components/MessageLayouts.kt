package app.photon.ui.shared.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.photon.data.model.Message
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun formatTime(ts: Long) = timeFormat.format(Date(ts * 1000))

private val dimColor = Color(0xFF666666)
private val faintColor = Color(0xFF444444)
// Outgoing-message status indicator. Single line per layout so we don't
// stack chrome on the happy path. "failed" gets a clickable label that
// invokes the retry callback the screen passes down.
@Composable
private fun StatusLine(
    msg: Message,
    monospace: Boolean,
    onRetry: (() -> Unit)?,
) {
    if (!msg.isFromMe) return
    when (msg.status) {
        "sending" -> Text(
            text = "sending…",
            fontSize = 10.sp,
            color = faintColor,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        )
        "failed" -> Text(
            text = "! failed, tap to retry",
            fontSize = 10.sp,
            color = Color(0xFFCC4444),
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            modifier = if (onRetry != null) Modifier.clickable(onClick = onRetry) else Modifier,
        )
        else -> {}
    }
}

private fun formatReactionsInline(msg: Message): String {
    if (msg.reactions.isEmpty()) return ""
    return msg.reactions.groupBy { it.emoji }.entries.joinToString(" ") { (emoji, rs) ->
        if (rs.size > 1) "$emoji${rs.size}" else emoji
    }
}

/**
 * Compact one-line quote shown above a message that is a reply. Caller passes the
 * human-readable name for the quoted message's sender (empty means "You"/self).
 */
@Composable
private fun ReplyQuoteLine(
    quoted: Message,
    quotedSenderName: String?,
    indentStart: androidx.compose.ui.unit.Dp = 0.dp,
    monospace: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val who = when {
        quoted.isFromMe -> "You"
        !quotedSenderName.isNullOrBlank() -> quotedSenderName
        else -> quoted.senderJid.substringBefore("@").take(8)
    }
    val preview = when {
        !quoted.textBody.isNullOrBlank() -> quoted.textBody.replace('\n', ' ')
        else -> "[ ${quoted.contentType} ]"
    }.take(60)
    Text(
        text = "↳ $who: $preview",
        fontSize = 11.sp,
        color = dimColor,
        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(start = indentStart)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    )
}

// ─── Terminal Layout ─────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalMessageRow(
    msg: Message,
    isGroup: Boolean,
    onMediaTap: () -> Unit,
    onLongPress: () -> Unit = {},
    senderName: String? = null,
    quotedSenderName: String? = null,
    onReplyPreviewTap: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    val time = formatTime(msg.timestamp)
    val prefix = if (msg.isFromMe) "> " else ""
    val senderTag = if (isGroup && !msg.isFromMe) {
        val name = senderName ?: msg.senderJid.substringBefore("@").takeLast(6)
        " $name"
    } else ""

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (isMedia(msg)) onMediaTap() },
                onLongClick = onLongPress,
            ),
    ) {
        msg.replyToPreview?.let { quoted ->
            ReplyQuoteLine(
                quoted = quoted,
                quotedSenderName = quotedSenderName,
                indentStart = 56.dp,
                monospace = true,
                onClick = onReplyPreviewTap,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        ) {
            Text(
                text = "[$time$senderTag]",
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = dimColor,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = prefix + formatContent(msg),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = if (isDeletedOrEmpty(msg)) faintColor else Color.White,
            )
        }

        if (msg.reactions.isNotEmpty()) {
            Text(
                text = "  └ ${formatReactionsInline(msg)}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = dimColor,
                modifier = Modifier.padding(start = 56.dp),
            )
        }
        Row(modifier = Modifier.padding(start = 56.dp)) {
            StatusLine(msg, monospace = true, onRetry = onRetry)
        }
    }
}

// ─── Clean Layout (tap for time) ─────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CleanMessageRow(
    msg: Message,
    isGroup: Boolean,
    onMediaTap: () -> Unit,
    onLongPress: () -> Unit = {},
    senderName: String? = null,
    quotedSenderName: String? = null,
    onReplyPreviewTap: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    var showTime by remember { mutableStateOf(false) }
    val alignment = if (msg.isFromMe) TextAlign.End else TextAlign.Start

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isMedia(msg)) onMediaTap() else showTime = !showTime
                },
                onLongClick = onLongPress,
            )
            .padding(vertical = 3.dp),
        horizontalAlignment = if (msg.isFromMe) Alignment.End else Alignment.Start,
    ) {
        // Sender name in groups
        if (isGroup && !msg.isFromMe) {
            Text(
                text = senderName ?: msg.senderJid.substringBefore("@"),
                fontSize = 11.sp,
                color = faintColor,
            )
        }

        msg.replyToPreview?.let { quoted ->
            ReplyQuoteLine(
                quoted = quoted,
                quotedSenderName = quotedSenderName,
                onClick = onReplyPreviewTap,
            )
        }

        Row(verticalAlignment = Alignment.Bottom) {
            if (showTime && !msg.isFromMe) {
                Text(
                    text = formatTime(msg.timestamp),
                    fontSize = 11.sp,
                    color = faintColor,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }

            Text(
                text = formatContent(msg),
                fontSize = 15.sp,
                color = if (isDeletedOrEmpty(msg)) faintColor else Color.White,
                textAlign = alignment,
            )

            if (showTime && msg.isFromMe) {
                Text(
                    text = formatTime(msg.timestamp),
                    fontSize = 11.sp,
                    color = faintColor,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }

        // Status for sent messages: sending / failed always visible (so the
        // user notices and can retry); the read/delivered/sent checkmarks
        // stay hidden until the user taps to reveal the timestamp.
        if (msg.isFromMe) {
            when (msg.status) {
                "sending", "failed" -> StatusLine(msg, monospace = false, onRetry = onRetry)
                else -> if (showTime) {
                    Text(
                        text = when (msg.status) {
                            "read" -> "\u2713\u2713"
                            "delivered" -> "\u2713\u2713"
                            "sent" -> "\u2713"
                            else -> ""
                        },
                        fontSize = 11.sp,
                        color = if (msg.status == "read") Color(0xFF5599FF) else faintColor,
                    )
                }
            }
        }

        if (msg.reactions.isNotEmpty()) {
            Text(
                text = "└ ${formatReactionsInline(msg)}",
                fontSize = 12.sp,
                color = dimColor,
            )
        }
    }

    HorizontalDivider(color = Color(0xFF0D0D0D), thickness = 0.5.dp)
}

// ─── Transcript Layout ───────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranscriptMessageRow(
    msg: Message,
    isGroup: Boolean,
    senderName: String?,
    onMediaTap: () -> Unit,
    onLongPress: () -> Unit = {},
    quotedSenderName: String? = null,
    onReplyPreviewTap: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    val name = if (msg.isFromMe) {
        "You"
    } else {
        senderName ?: msg.senderJid.substringBefore("@")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (isMedia(msg)) onMediaTap() },
                onLongClick = onLongPress,
            ),
    ) {
        msg.replyToPreview?.let { quoted ->
            ReplyQuoteLine(
                quoted = quoted,
                quotedSenderName = quotedSenderName,
                indentStart = 64.dp,
                onClick = onReplyPreviewTap,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = name,
                fontSize = 13.sp,
                color = dimColor,
                modifier = Modifier.width(64.dp),
                maxLines = 1,
            )
            Text(
                text = formatContent(msg),
                fontSize = 15.sp,
                color = if (isDeletedOrEmpty(msg)) faintColor else Color.White,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatTime(msg.timestamp),
                fontSize = 11.sp,
                color = faintColor,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        if (msg.reactions.isNotEmpty()) {
            Text(
                text = "└ ${formatReactionsInline(msg)}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = dimColor,
                modifier = Modifier.padding(start = 64.dp),
            )
        }
        Row(modifier = Modifier.padding(start = 64.dp)) {
            StatusLine(msg, monospace = false, onRetry = onRetry)
        }
    }
}

// ─── Shared helpers ──────────────────────────────────────────────

private fun formatContent(msg: Message): String {
    return when (msg.contentType) {
        "text" -> msg.textBody?.takeIf { it.isNotBlank() } ?: "[ deleted ]"
        "image" -> {
            val caption = msg.textBody?.takeIf { it.isNotBlank() }
            if (caption != null) "[ image ] $caption" else "[ image ]"
        }
        "video" -> "[ video ]"
        "audio" -> "[ audio ]"
        "document" -> "[ ${msg.textBody ?: "document"} ]"
        "sticker" -> "[ sticker ]"
        "location" -> "[ location ]"
        "contact" -> "[ contact: ${msg.textBody ?: ""} ]"
        else -> "[ ${msg.contentType} ]"
    }
}

private fun isDeletedOrEmpty(msg: Message): Boolean {
    return msg.contentType == "text" && msg.textBody.isNullOrBlank()
}

private fun isMedia(msg: Message): Boolean {
    return msg.contentType in listOf("image", "video", "audio", "document", "sticker")
}
