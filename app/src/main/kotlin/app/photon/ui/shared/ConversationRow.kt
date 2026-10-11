package app.photon.ui.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.photon.data.model.Conversation
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Hoisted so each visible row doesn't allocate formatters on every
// recomposition (same pattern as MessageLayouts.kt).
private val hourMinuteFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val weekdayFormat = SimpleDateFormat("EEE", Locale.getDefault())
private val dayMonthFormat = SimpleDateFormat("dd/MM", Locale.getDefault())

@Composable
fun ConversationRow(
    conv: Conversation,
    onClick: () -> Unit,
    icon: Painter? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = Color(0xFF444444),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(14.dp))
        }

        Text(
            text = conv.name?.takeIf { it.isNotBlank() } ?: conv.jid,
            fontSize = 18.sp,
            letterSpacing = 1.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Text(
            text = formatTimestamp(conv.lastTimestamp),
            fontSize = 13.sp,
            letterSpacing = 1.sp,
            color = Color(0xFF666666),
            modifier = Modifier.padding(start = 16.dp),
        )

        if (conv.unreadCount > 0) {
            Surface(
                shape = CircleShape,
                color = Color.White,
                modifier = Modifier.padding(start = 12.dp).size(22.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (conv.unreadCount > 99) "99+" else conv.unreadCount.toString(),
                        fontSize = 10.sp,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
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
