package app.photon.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.photon.data.ListScrollSpeed
import app.photon.data.ScrollSpeed
import app.photon.ui.shared.ScrollDialEffect
import androidx.compose.ui.unit.sp
import app.photon.data.MessageLayout
import app.photon.data.PhotonPreferences
import app.photon.service.PhotonService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val divColor = Color(0xFF1A1A1A)
private val divMinor = Color(0xFF111111)
private val dim = Color(0xFF666666)
private val destructive = Color(0xFFCC4444)

private enum class Page { HOME, CONNECTIONS, WHATSAPP, SIGNAL, CHAT, STORAGE }

@Composable
fun SettingsScreen(onBack: () -> Unit, onResetWhatsApp: () -> Unit = {}, onResetSignal: () -> Unit = {}) {
    var page by remember { mutableStateOf(Page.HOME) }

    when (page) {
        Page.HOME -> HomePage(onBack, onNav = { page = it })
        Page.CONNECTIONS -> ConnectionsPage(onBack = { page = Page.HOME }, onNav = { page = it })
        Page.WHATSAPP -> WhatsAppPage(onBack = { page = Page.CONNECTIONS }, onReset = onResetWhatsApp)
        Page.SIGNAL -> SignalPage(onBack = { page = Page.CONNECTIONS }, onReset = onResetSignal)
        Page.CHAT -> ChatPage(onBack = { page = Page.HOME })
        Page.STORAGE -> StoragePage(onBack = { page = Page.HOME })
    }
}

// ─── Home ───────────────────────────────────────────────────────

@Composable
private fun HomePage(onBack: () -> Unit, onNav: (Page) -> Unit) {
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Header("SETTINGS", onBack)
        Spacer(Modifier.height(32.dp))
        HorizontalDivider(color = divColor)
        NavRow("CONNECTIONS") { onNav(Page.CONNECTIONS) }
        HorizontalDivider(color = divMinor)
        NavRow("CHAT") { onNav(Page.CHAT) }
        HorizontalDivider(color = divMinor)
        NavRow("STORAGE") { onNav(Page.STORAGE) }
        HorizontalDivider(color = divMinor)
        Spacer(Modifier.weight(1f))
        HorizontalDivider(color = divColor)
        Text(
            text = "PHOTON 0.3.0",
            fontSize = 12.sp, letterSpacing = 2.sp, color = Color(0xFF333333),
            modifier = Modifier.padding(vertical = 20.dp),
        )
    }
}

// ─── Connections Overview ────────────────────────────────────────

@Composable
private fun ConnectionsPage(onBack: () -> Unit, onNav: (Page) -> Unit) {
    val ws = PhotonService._wsClient
    val waState by (ws?.whatsappState ?: MutableStateFlow("disconnected")).collectAsState()
    val sigState by (PhotonService._signalReceiver?.state ?: MutableStateFlow("disconnected")).collectAsState()
    val sigRegistered = PhotonService._signalCredentials?.isRegistered() == true

    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Header("CONNECTIONS", onBack)
        Spacer(Modifier.height(32.dp))
        HorizontalDivider(color = divColor)
        StatusNavRow("WHATSAPP", formatState(waState)) { onNav(Page.WHATSAPP) }
        HorizontalDivider(color = divMinor)
        StatusNavRow("SIGNAL", if (sigRegistered) formatState(sigState) else "NOT LINKED") { onNav(Page.SIGNAL) }
        HorizontalDivider(color = divMinor)
    }
}

// ─── WhatsApp Detail ────────────────────────────────────────────

@Composable
private fun WhatsAppPage(onBack: () -> Unit, onReset: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val ws = PhotonService._wsClient
    val waState by (ws?.whatsappState ?: MutableStateFlow("disconnected")).collectAsState()

    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Header("WHATSAPP", onBack)
        Spacer(Modifier.height(32.dp))
        HorizontalDivider(color = divColor)

        InfoRow("STATUS", formatState(waState), stateColor(waState))
        HorizontalDivider(color = divMinor)

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = divColor)

        if (waState == "connected" || waState == "disconnected") {
            ActionRow("REFRESH CONNECTION") {
                scope.launch {
                    try {
                        ws?.disconnect()
                        ws?.connect()
                    } catch (_: Exception) {}
                }
            }
            HorizontalDivider(color = divMinor)
        }

        if (waState == "connected" || waState == "disconnected") {
            ActionRow("RESET") {
                scope.launch {
                    // Stop bridge, delete data, restart bridge
                    PhotonService._wsClient?.disconnect()
                    PhotonService._goBridge?.stop()
                    kotlinx.coroutines.delay(300)
                    val filesDir = context.filesDir
                    filesDir.listFiles()?.filter {
                        it.name.startsWith("messages.db") || it.name.startsWith("whatsmeow.db")
                    }?.forEach { it.delete() }
                    java.io.File(filesDir, "media").deleteRecursively()
                    java.io.File(filesDir, "thumbs").deleteRecursively()
                    PhotonService._goBridge?.start()
                    kotlinx.coroutines.delay(1000)
                    try { PhotonService._wsClient?.connect() } catch (_: Exception) {}
                    onReset()
                }
            }
        }
        HorizontalDivider(color = divMinor)
    }
}

// ─── Signal Detail ──────────────────────────────────────────────

@Composable
private fun SignalPage(onBack: () -> Unit, onReset: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val sigState by (PhotonService._signalReceiver?.state ?: MutableStateFlow("disconnected")).collectAsState()
    val sigRegistered = PhotonService._signalCredentials?.isRegistered() == true
    val phone = PhotonService._signalCredentials?.phoneNumber
    val deviceId = PhotonService._signalCredentials?.storedDeviceId

    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Header("SIGNAL", onBack)
        Spacer(Modifier.height(32.dp))
        HorizontalDivider(color = divColor)

        InfoRow("STATUS", if (sigRegistered) formatState(sigState) else "NOT LINKED", stateColor(if (sigRegistered) sigState else ""))
        HorizontalDivider(color = divMinor)

        if (phone != null) {
            InfoRow("PHONE", phone)
            HorizontalDivider(color = divMinor)
        }

        if (deviceId != null && sigRegistered) {
            InfoRow("DEVICE", deviceId.toString())
            HorizontalDivider(color = divMinor)
        }

        if (sigRegistered) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = divColor)

            ActionRow("REFRESH CONNECTION") {
                scope.launch {
                    PhotonService._signalReceiver?.stop()
                    PhotonService._signalReceiver?.start()
                }
            }
            HorizontalDivider(color = divMinor)

            ActionRow("RESET") {
                scope.launch {
                    PhotonService._signalReceiver?.stop()
                    PhotonService._signalSender?.shutdown()
                    PhotonService._signalManager?.logout()
                    val dbDir = context.getDatabasePath("signal_protocol.db").parentFile
                    dbDir?.listFiles()?.filter { it.name.startsWith("signal_") }?.forEach { it.delete() }
                    onReset()
                }
            }
            HorizontalDivider(color = divMinor)
        }
    }
}

// ─── Chat Settings ──────────────────────────────────────────────

@Composable
private fun ChatPage(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = PhotonPreferences(context)

    val showThumbnails by prefs.showThumbnails.collectAsState(initial = true)
    val dmLayout by prefs.dmLayout.collectAsState(initial = MessageLayout.TERMINAL)
    val groupLayout by prefs.groupLayout.collectAsState(initial = MessageLayout.TRANSCRIPT)
    val notificationsEnabled by prefs.notificationsEnabled.collectAsState(initial = true)
    val chatScrollSpeed by prefs.chatScrollSpeed.collectAsState(initial = ScrollSpeed.MEDIUM)
    val menuScrollSpeed by prefs.menuScrollSpeed.collectAsState(initial = ListScrollSpeed.SLOW)

    val scrollState = rememberScrollState()
    val scrollPx = with(LocalDensity.current) { 40.dp.toPx() }
    ScrollDialEffect { dir -> scrollState.scrollBy((dir * scrollPx).toInt().toFloat()) }

    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp)) {
        Header("CHAT", onBack)
        Spacer(Modifier.height(32.dp))
        HorizontalDivider(color = divColor)

        CycleRow("NOTIFICATIONS", if (notificationsEnabled) "ON" else "OFF") {
            scope.launch { prefs.setNotificationsEnabled(!notificationsEnabled) }
        }
        HorizontalDivider(color = divMinor)
        CycleRow("DM LAYOUT", dmLayout.label) { scope.launch { prefs.setDmLayout(dmLayout.next()) } }
        HorizontalDivider(color = divMinor)
        CycleRow("GROUP LAYOUT", groupLayout.label) { scope.launch { prefs.setGroupLayout(groupLayout.next()) } }
        HorizontalDivider(color = divMinor)
        CycleRow("THUMBNAILS", if (showThumbnails) "ON" else "OFF") { scope.launch { prefs.setShowThumbnails(!showThumbnails) } }
        HorizontalDivider(color = divMinor)
        CycleRow("CHAT SCROLL", chatScrollSpeed.label) { scope.launch { prefs.setChatScrollSpeed(chatScrollSpeed.next()) } }
        HorizontalDivider(color = divMinor)
        CycleRow("MENU SCROLL", menuScrollSpeed.label) { scope.launch { prefs.setMenuScrollSpeed(menuScrollSpeed.next()) } }
        HorizontalDivider(color = divMinor)
    }
}

// ─── Storage Settings ───────────────────────────────────────────

@Composable
private fun StoragePage(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = PhotonPreferences(context)
    val ws = PhotonService._wsClient

    val maxMessages by prefs.maxMessages.collectAsState(initial = 50)
    val maxDays by prefs.maxDays.collectAsState(initial = 7)

    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Header("STORAGE", onBack)
        Spacer(Modifier.height(32.dp))
        HorizontalDivider(color = divColor)

        ValueCycleRow("MESSAGES PER CHAT", maxMessages.toString(), listOf(25, 50, 100, 200), maxMessages) { v ->
            scope.launch { prefs.setMaxMessages(v); syncRetention(ws, v, maxDays) }
        }
        HorizontalDivider(color = divMinor)
        ValueCycleRow("MESSAGE HISTORY", "$maxDays DAYS", listOf(1, 3, 7, 14, 30), maxDays) { v ->
            scope.launch { prefs.setMaxDays(v); syncRetention(ws, maxMessages, v) }
        }
        HorizontalDivider(color = divMinor)
    }
}

// ─── Shared Components ──────────────────────────────────────────

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
    ) {
        Text("<", fontSize = 18.sp, color = dim,
            modifier = Modifier.align(Alignment.CenterStart).clickable(onClick = onBack).padding(end = 16.dp))
        Text(title, fontSize = 13.sp, letterSpacing = 3.sp, color = dim,
            modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 18.sp, letterSpacing = 2.sp, color = Color.White,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 22.dp),
    )
}

@Composable
private fun StatusNavRow(label: String, state: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 22.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 18.sp, letterSpacing = 2.sp, color = Color.White)
        Text(state, fontSize = 14.sp, letterSpacing = 1.sp, color = dim)
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = dim) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 22.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 18.sp, letterSpacing = 2.sp, color = Color.White)
        Text(value, fontSize = 14.sp, letterSpacing = 1.sp, color = valueColor)
    }
}

@Composable
private fun CycleRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 22.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 18.sp, letterSpacing = 2.sp, color = Color.White)
        Text(value, fontSize = 14.sp, letterSpacing = 1.sp, color = dim)
    }
}

@Composable
private fun <T> ValueCycleRow(label: String, display: String, options: List<T>, current: T, onSelect: (T) -> Unit) {
    val next = (options.indexOf(current) + 1) % options.size
    CycleRow(label, display) { onSelect(options[next]) }
}

@Composable
private fun ActionRow(label: String, color: Color = Color.White, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 18.sp, letterSpacing = 2.sp, color = color,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 22.dp),
    )
}

private fun stateColor(state: String) = when (state) {
    "connected" -> Color.White
    else -> Color(0xFF666666)
}

private fun formatState(state: String) = when (state) {
    "connected" -> "CONNECTED"
    "connecting" -> "CONNECTING"
    "logged_out" -> "NOT CONNECTED"
    "disconnected" -> "DISCONNECTED"
    else -> state.uppercase()
}

private suspend fun syncRetention(ws: app.photon.service.WsClient?, maxMessages: Int, maxDays: Int) {
    try {
        ws?.request("set_retention", buildJsonObject {
            put("max_messages", maxMessages)
            put("max_days", maxDays)
            put("media_ttl_mins", 5)
        })
    } catch (_: Exception) {}
}
