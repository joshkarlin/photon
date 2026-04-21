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
            .padding(vertical = 22.dp),
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

fun formatTimestamp(epochSeconds: Long): String {
    if (epochSeconds == 0L) return ""
    val date = Date(epochSeconds * 1000)
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { time = date }
    return when {
        now.get(Calendar.DATE) == then.get(Calendar.DATE) &&
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ->
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        now.get(Calendar.DATE) - then.get(Calendar.DATE) == 1 &&
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> "Yesterday"
        now.get(Calendar.WEEK_OF_YEAR) == then.get(Calendar.WEEK_OF_YEAR) &&
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ->
            SimpleDateFormat("EEE", Locale.getDefault()).format(date)
        else -> SimpleDateFormat("dd/MM", Locale.getDefault()).format(date)
    }
}
