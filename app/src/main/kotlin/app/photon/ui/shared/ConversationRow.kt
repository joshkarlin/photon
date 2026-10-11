package app.photon.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import app.photon.data.model.Conversation
import app.photon.data.model.Platform
import app.photon.data.names.DisplayNames
import app.photon.ui.theme.PhotonGrid
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Hoisted so each visible row doesn't allocate formatters on every
// recomposition (same pattern as MessageLayouts.kt).
private val hourMinuteFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val weekdayFormat = SimpleDateFormat("EEE", Locale.getDefault())
private val dayMonthFormat = SimpleDateFormat("dd/MM", Locale.getDefault())

/**
 * One conversation in a chat list: name, an unread count, a muted mark and
 * the last-activity time. A muted chat still shows its count, as in WhatsApp.
 * Fixed height ([PhotonGrid.LIST_ROW_HEIGHT]) so the list can use the
 * SDK's uniform-row scroll bar.
 *
 * The row spans the list's full width, so all of it can be tapped, and
 * insets its content by the page margin. The time always ends the row, so
 * times form one column down the list; the count sits before it.
 *
 * @param platform whose naming rules the title follows
 * @param icon platform icon, shown in All Chats
 */
@Composable
fun ConversationRow(
    conv: Conversation,
    platform: Platform,
    onClick: () -> Unit,
    icon: Painter? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PhotonGrid.LIST_ROW_HEIGHT.gridUnitsAsDp())
            .lightClickable(onClick = onClick)
            .padding(horizontal = PhotonGrid.MARGIN.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.spacedBy(0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = LightThemeTokens.colors.contentSecondary,
                modifier = Modifier.size(1.5f.gridUnitsAsDp()),
            )
        }

        LightText(
            text = remember(conv.jid, conv.name, conv.isGroup, platform) { DisplayNames.title(conv, platform) },
            variant = LightTextVariant.Heading,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (conv.unreadCount > 0) {
            UnreadBadge(conv.unreadCount)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(0.375f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (conv.isMuted) {
                Icon(
                    painter = painterResource(LightIcons.SPEAKER_MUTED.drawableResource),
                    contentDescription = "Muted",
                    tint = LightThemeTokens.colors.contentSecondary,
                    modifier = Modifier.size(1f.gridUnitsAsDp()),
                )
            }
            LightText(
                text = formatTimestamp(conv.lastTimestamp),
                variant = LightTextVariant.Detail,
                lighten = true,
                maxLines = 1,
            )
        }
    }
}

/** Unread count, inverted (black on white) so it reads without colour. */
@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .size(1.75f.gridUnitsAsDp())
            .background(LightThemeTokens.colors.content, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = if (count > 99) "99+" else count.toString(),
            variant = LightTextVariant.Superfine,
            color = LightThemeTokens.colors.background,
            maxLines = 1,
        )
    }
}

/**
 * List timestamp: "14:02" today, "Yesterday", a weekday within this week,
 * otherwise "dd/MM". Days are calendar days in the device's time zone.
 */
fun formatTimestamp(epochSeconds: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (epochSeconds == 0L) return ""
    val date = Date(epochSeconds * 1000)
    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val then = Calendar.getInstance().apply { time = date }
    return when (calendarDaysBetween(then, now)) {
        0L -> hourMinuteFormat.format(date)
        1L -> "Yesterday"
        else -> if (now.get(Calendar.WEEK_OF_YEAR) == then.get(Calendar.WEEK_OF_YEAR) &&
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
        ) weekdayFormat.format(date) else dayMonthFormat.format(date)
    }
}

private fun calendarDaysBetween(from: Calendar, to: Calendar): Long {
    fun startOfDay(c: Calendar) = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    // Rounded so a DST shift between the two days doesn't cost a day.
    return Math.round((startOfDay(to) - startOfDay(from)) / 86_400_000.0)
}
