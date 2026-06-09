package app.photon.ui.whatsapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.photon.data.model.int
import app.photon.service.PhotonService
import app.photon.ui.shared.ChatListContent

@Composable
fun ChatListScreen(onChat: (String) -> Unit, onSwitch: () -> Unit, onSettings: () -> Unit) {
    val repo = PhotonService._chatRepository ?: return
    val rawConversations by repo.conversations.collectAsState()
    val conversations = rawConversations?.filter { conv ->
        !conv.isPseudoChat && conv.lastTimestamp > 0
    }

    ChatListContent(
        title = "WHATSAPP",
        onTitleClick = onSwitch,
        onSettings = onSettings,
        conversations = conversations,
        onChat = onChat,
        emptyContent = { WhatsAppSyncStatus() },
    )
}

@Composable
private fun WhatsAppSyncStatus() {
    val ws = PhotonService._wsClient
    val waState = ws?.whatsappState?.collectAsState()?.value ?: "disconnected"
    var syncProgress by remember { mutableStateOf("") }
    var syncDone by remember { mutableStateOf(false) }

    LaunchedEffect(ws) {
        ws?.events?.collect { evt ->
            when (evt.type) {
                "history_sync_progress" -> {
                    val done = evt.int("conversations_done") ?: 0
                    val total = evt.int("conversations_total") ?: 0
                    val msgs = evt.int("messages_total") ?: 0
                    syncProgress = "$done / $total CHATS · $msgs MESSAGES"
                }
                "history_sync_complete" -> syncDone = true
            }
        }
    }

    Box(Modifier.fillMaxWidth().fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = when {
                    syncDone -> "NO MESSAGES YET"
                    waState == "connected" -> "SYNCING"
                    waState == "connecting" -> "CONNECTING"
                    PhotonService._wsClient?.isConnected?.value == true -> "PAIRING"
                    else -> "CONNECTING"
                },
                fontSize = 18.sp, letterSpacing = 3.sp, color = Color.White,
            )
            if (syncProgress.isNotEmpty() && !syncDone) {
                Spacer(Modifier.height(12.dp))
                Text(syncProgress, fontSize = 12.sp, letterSpacing = 1.sp, color = Color(0xFF666666))
            }
            if (syncDone) {
                Spacer(Modifier.height(12.dp))
                Text("NEW MESSAGES WILL APPEAR HERE", fontSize = 12.sp, letterSpacing = 1.sp, color = Color(0xFF666666))
            }
        }
    }
}
