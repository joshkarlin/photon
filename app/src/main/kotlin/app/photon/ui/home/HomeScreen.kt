package app.photon.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.photon.R

@Composable
fun HomeScreen(
    onWhatsApp: () -> Unit,
    onSignal: () -> Unit,
    onSms: () -> Unit = {},
    onAllChats: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        // Title area
        Spacer(Modifier.weight(0.6f))
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "PHOTON",
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 14.sp,
                color = Color.White,
            )
        }

        // Icons — centered between title and settings
        Spacer(Modifier.weight(0.5f))
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // SMS
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onSms)
                    .padding(vertical = 16.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_sms),
                    contentDescription = "SMS",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text("SMS", fontSize = 9.sp, letterSpacing = 2.sp, color = Color(0xFF666666))
            }

            // WhatsApp
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onWhatsApp)
                    .padding(vertical = 16.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_whatsapp),
                    contentDescription = "WhatsApp",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text("WHATSAPP", fontSize = 9.sp, letterSpacing = 2.sp, color = Color(0xFF666666))
            }

            // Signal
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onSignal)
                    .padding(vertical = 16.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_signal),
                    contentDescription = "Signal",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text("SIGNAL", fontSize = 9.sp, letterSpacing = 2.sp, color = Color(0xFF666666))
            }
        }

        Spacer(Modifier.weight(0.5f))

        // Bottom bar
        HorizontalDivider(color = Color(0xFF1A1A1A))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Text(
                text = "ALL",
                fontSize = 14.sp,
                letterSpacing = 3.sp,
                color = Color(0xFF666666),
                modifier = Modifier
                    .clickable(onClick = onAllChats)
                    .padding(vertical = 20.dp, horizontal = 16.dp),
            )
            Text(
                text = "SETTINGS",
                fontSize = 14.sp,
                letterSpacing = 3.sp,
                color = Color(0xFF666666),
                modifier = Modifier
                    .clickable(onClick = onSettings)
                    .padding(vertical = 20.dp, horizontal = 16.dp),
            )
        }
    }
}
