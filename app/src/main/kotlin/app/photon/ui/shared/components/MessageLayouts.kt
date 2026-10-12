package app.photon.ui.shared.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import app.photon.data.model.ContentTypes
import app.photon.data.model.Message
import app.photon.data.model.MessageStatus
import app.photon.data.names.DisplayNames
import app.photon.service.UNAVAILABLE_LABEL
import app.photon.service.locationLabel
import app.photon.service.notificationPreview
import app.photon.service.viewOnceLabel
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
 * (the next older one). Call rows and system lines always stand alone.
 */
internal fun startsBurst(msg: Message, previous: Message?): Boolean = when {
    previous == null -> true
    standsAlone(msg) || standsAlone(previous) -> true
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
        // A document's body is its file name: WhatsApp's, and those Photon sends.
        "document" -> if (body != null) "[ document: $body ]" else "[ document ]"
        "sticker" -> "[ sticker ]"
        ContentTypes.LOCATION -> locationLabel(body)
        "contact" -> "[ contact: ${body ?: ""} ]"
        "call" -> body ?: "Call"
        ContentTypes.SYSTEM -> body.orEmpty()
        // Content that's only on the user's phone: say what it is and where it is.
        ContentTypes.VIEW_ONCE -> "[ ${viewOnceLabel(body)} · $ON_YOUR_PHONE ]"
        ContentTypes.UNAVAILABLE -> "[ $UNAVAILABLE_LABEL · $ON_YOUR_PHONE ]"
        // Unknown types: the platforms store a readable placeholder (e.g.
        // "[ unsupported message ]") in the body when they have one.
        else -> body ?: "[ ${msg.contentType} ]"
    }
}

private const val ON_YOUR_PHONE = "open on your phone"

/**
 * Bodies with no message content of their own — a deleted message, a
 * bracketed placeholder such as "[ unsupported message ]", or content only
 * the user's phone has ([isPhoneOnly]) — which recede.
 */
internal fun isPlaceholder(msg: Message): Boolean {
    val body = msg.textBody?.trim()
    val isBracketed = body != null && body.startsWith("[") && body.endsWith("]")
    return when (msg.contentType) {
        "text" -> body.isNullOrEmpty() || isBracketed
        "image", "video", "audio", "document", "sticker", "contact", "call",
        ContentTypes.LOCATION, ContentTypes.SYSTEM -> false
        ContentTypes.VIEW_ONCE, ContentTypes.UNAVAILABLE -> true
        else -> body.isNullOrEmpty() || isBracketed
    }
}

internal fun isCall(msg: Message): Boolean = msg.contentType == "call"

/** A centred informational line, such as a disappearing-messages change. */
internal fun isSystem(msg: Message): Boolean = msg.contentType == ContentTypes.SYSTEM

/** Rows that never join a burst: calls and system lines. */
private fun standsAlone(msg: Message): Boolean = isCall(msg) || isSystem(msg)

/**
 * A message whose content only the user's phone has: view-once media, which
 * Photon never downloads, and a message not yet delivered to this device.
 * Photon can show that it's there, and delete it here, nothing more.
 */
internal fun isPhoneOnly(msg: Message): Boolean =
    msg.contentType == ContentTypes.VIEW_ONCE || msg.contentType == ContentTypes.UNAVAILABLE

internal fun isLocation(msg: Message): Boolean = msg.contentType == ContentTypes.LOCATION

/** A place, from a location's media_url (`geo:<lat>,<lng>`). */
internal data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * The point in a `geo:` URI ("geo:51.5007,-0.1246", with or without an
 * altitude or parameters); null for anything else, or coordinates off the
 * globe.
 */
internal fun parseGeo(uri: String?): GeoPoint? {
    if (uri == null || !uri.startsWith("geo:", ignoreCase = true)) return null
    val coordinates = uri.substring(4).substringBefore('?').substringBefore(';').split(',')
    if (coordinates.size < 2) return null
    val latitude = coordinates[0].trim().toDoubleOrNull() ?: return null
    val longitude = coordinates[1].trim().toDoubleOrNull() ?: return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return GeoPoint(latitude, longitude)
}

/** "51.5007, -0.1246": four decimals, about 10m, whatever the device's locale. */
internal fun formatCoordinates(point: GeoPoint): String =
    String.format(Locale.ROOT, "%.4f, %.4f", point.latitude, point.longitude)

/**
 * The map intent a location opens: its point, pinned with its name where it
 * has one (`geo:lat,lng?q=lat,lng(name)`, which map apps such as Organic
 * Maps read). Null when the location has no usable point.
 */
internal fun locationIntent(msg: Message): Intent? {
    val point = parseGeo(msg.mediaUrl) ?: return null
    val at = String.format(Locale.ROOT, "%.6f,%.6f", point.latitude, point.longitude)
    val name = msg.textBody?.trim()?.takeIf { it.isNotEmpty() }
    val uri = if (name == null) "geo:$at" else "geo:$at?q=$at(${Uri.encode(name)})"
    return Intent(Intent.ACTION_VIEW, Uri.parse(uri))
}

/** Opens a location in whatever map app answers `geo:`; with none, nothing happens. */
private fun openLocation(context: Context, msg: Message) {
    val intent = locationIntent(msg) ?: return
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}

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
    val preview = (notificationPreview(quoted.textBody, quoted.contentType) ?: "[ ${quoted.contentType} ]")
        .replace('\n', ' ')
        .take(60)
    return if (who == null) "↳ $preview" else "↳ $who: $preview"
}

/**
 * Tap and long-press without a press indication, like `lightClickable`. The
 * haptic fires on long-press only: message rows are touched constantly while
 * scrolling, so a finger-down buzz would be noise. No [onLongClick], no
 * long-press (and no buzz for nothing).
 */
internal fun Modifier.lightCombinedClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier = composed {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    combinedClickable(
        interactionSource = null,
        indication = null,
        onClick = onClick,
        onLongClick = onLongClick?.let { longClick ->
            {
                if (hapticsEnabled) LightHapticFeedback.click(context)
                longClick()
            }
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

/**
 * The message text itself. Placeholders recede. A location adds its
 * coordinates beneath, in Detail.
 */
@Composable
fun MessageBody(msg: Message, modifier: Modifier = Modifier, alignEnd: Boolean = false) {
    val align = if (alignEnd) TextAlign.End else TextAlign.Start
    val point = if (isLocation(msg)) parseGeo(msg.mediaUrl) else null
    if (point == null) {
        MessageText(msg, align, modifier)
        return
    }
    Column(modifier = modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        MessageText(msg, align)
        LightText(text = formatCoordinates(point), variant = LightTextVariant.Detail, lighten = true, align = align)
    }
}

@Composable
private fun MessageText(msg: Message, align: TextAlign, modifier: Modifier = Modifier) {
    LightText(
        text = formatContent(msg),
        variant = LightTextVariant.Paragraph,
        color = if (isPlaceholder(msg)) PhotonColors.Faint else null,
        align = align,
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
 * A system line, such as "Disappearing messages · 1 week": centred and
 * secondary, like a call row, and inert: no long-press, swipe or tap.
 */
@Composable
fun SystemRow(msg: Message) {
    LightText(
        text = formatContent(msg),
        variant = LightTextVariant.Detail,
        lighten = true,
        align = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A call log entry: one centred line, e.g. "Missed voice call · 14:02". No
 * reply or reaction; long-press offers delete only.
 */
@Composable
fun CallRow(msg: Message, onLongPress: (() -> Unit)? = null) {
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
 * timestamp in the same place (and delivery status below). Tapping media
 * opens it ([onMediaTap]), and tapping a location opens it in a map app.
 *
 * Groups pass [senderName], which puts the sender before the timestamp on
 * incoming bursts, as the LP2 group thread does ("Name  12:36"). DMs pass null.
 */
@Composable
fun MessageRow(
    msg: Message,
    startsBurst: Boolean,
    onMediaTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    senderName: String? = null,
    quotedSenderName: String? = null,
    onReplyPreviewTap: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    var revealed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val fromMe = msg.isFromMe
    val showName = senderName != null && !fromMe && startsBurst

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightCombinedClickable(
                onClick = {
                    when {
                        isMedia(msg) -> onMediaTap()
                        isLocation(msg) -> openLocation(context, msg)
                        else -> revealed = !revealed
                    }
                },
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
