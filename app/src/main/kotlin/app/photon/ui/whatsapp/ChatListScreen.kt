package app.photon.ui.whatsapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import app.photon.data.model.Platform
import app.photon.data.model.int
import app.photon.service.Sessions
import app.photon.ui.shared.ChatListContent
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

@Composable
fun ChatListScreen(onChat: (String) -> Unit, onSwitch: () -> Unit, onSettings: () -> Unit) {
    val repo = Sessions.whatsapp?.repository ?: return
    val rawConversations by repo.conversations.collectAsState()
    val conversations = rawConversations?.filter { conv ->
        !conv.isPseudoChat && conv.lastTimestamp > 0
    }

    ChatListContent(
        title = "WhatsApp",
        onTitleClick = onSwitch,
        onSettings = onSettings,
        conversations = conversations,
        platformOf = { Platform.WHATSAPP },
        onChat = onChat,
        emptyContent = { WhatsAppSyncStatus() },
    )
}

@Composable
private fun WhatsAppSyncStatus() {
    val bridge = Sessions.whatsapp?.bridge
    val waState = bridge?.whatsappState?.collectAsState()?.value ?: "disconnected"
    var syncProgress by remember { mutableStateOf("") }
    var syncDone by remember { mutableStateOf(false) }

    LaunchedEffect(bridge) {
        bridge?.events?.collect { evt ->
            when (evt.type) {
                "history_sync_progress" -> {
                    val done = evt.int("conversations_done") ?: 0
                    val total = evt.int("conversations_total") ?: 0
                    val msgs = evt.int("messages_total") ?: 0
                    syncProgress = "$done / $total chats · $msgs messages"
                }
                "history_sync_complete" -> syncDone = true
            }
        }
    }

    Box(Modifier.fillMaxWidth().fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
        ) {
            LightText(
                text = when {
                    syncDone -> "No messages yet"
                    waState == "connected" -> "Syncing"
                    waState == "connecting" -> "Connecting"
                    Sessions.whatsapp?.bridge?.isConnected?.value == true -> "Pairing"
                    else -> "Connecting"
                },
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
            )
            if (syncProgress.isNotEmpty() && !syncDone) {
                LightText(syncProgress, variant = LightTextVariant.Detail, lighten = true, align = TextAlign.Center)
            }
            if (syncDone) {
                LightText(
                    "New messages will appear here",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    align = TextAlign.Center,
                )
            }
        }
    }
}
