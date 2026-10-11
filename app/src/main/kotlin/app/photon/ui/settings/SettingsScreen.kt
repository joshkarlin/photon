package app.photon.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import app.photon.data.ComposeStyle
import app.photon.data.PhotonPreferences
import app.photon.data.ScrollDial
import app.photon.service.PhotonService
import app.photon.ui.shared.components.MenuRow
import app.photon.ui.shared.components.MenuScreen
import app.photon.ui.theme.PhotonTheme
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    BackHandler { onBack() }
    SettingsHomeContent(version = version, onBack = onBack, onNav = onNav)
}

@Composable
private fun SettingsHomeContent(version: String?, onBack: () -> Unit, onNav: (Page) -> Unit) {
    MenuScreen("Settings", onBack) {
        MenuRow("CONNECTIONS") { onNav(Page.CONNECTIONS) }
        MenuRow("CHAT") { onNav(Page.CHAT) }
        MenuRow("STORAGE") { onNav(Page.STORAGE) }
        if (version != null) {
            LightText(
                text = "PHOTON $version",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
            )
        }
    }
}

// ─── Connections Overview ────────────────────────────────────────

@Composable
private fun ConnectionsPage(onBack: () -> Unit, onNav: (Page) -> Unit) {
    val bridge = PhotonService._bridgeClient
    val waState by (bridge?.whatsappState ?: MutableStateFlow("disconnected")).collectAsState()
    val sigState by (PhotonService._signalReceiver?.state ?: MutableStateFlow("disconnected")).collectAsState()
    val sigRegistered = PhotonService._signalCredentials?.isRegistered() == true

    BackHandler { onBack() }
    MenuScreen("Connections", onBack) {
        MenuRow("WHATSAPP", formatState(waState)) { onNav(Page.WHATSAPP) }
        MenuRow("SIGNAL", if (sigRegistered) formatState(sigState) else "NOT LINKED") { onNav(Page.SIGNAL) }
    }
}

// ─── WhatsApp Detail ────────────────────────────────────────────

@Composable
private fun WhatsAppPage(onBack: () -> Unit, onReset: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val bridge = PhotonService._bridgeClient
    val waState by (bridge?.whatsappState ?: MutableStateFlow("disconnected")).collectAsState()

    BackHandler { onBack() }
    MenuScreen("WhatsApp", onBack) {
        MenuRow("STATUS", formatState(waState), emphasizeValue = waState == "connected")

        if (waState == "logged_out") {
            // Not paired: the only useful action is to start the pairing flow.
            MenuRow("LINK DEVICE", onClick = onReset)
        } else {
            MenuRow("REFRESH CONNECTION", onClick = {
                scope.launch {
                    try {
                        bridge?.reconnectWhatsApp()
                    } catch (_: Exception) {}
                }
            })
            MenuRow("RESET", onClick = {
                scope.launch {
                    PhotonService.resetWhatsApp(context.filesDir)
                    onReset()
                }
            })
        }
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
    MenuScreen("Signal", onBack) {
        MenuRow(
            "STATUS",
            if (sigRegistered) formatState(sigState) else "NOT LINKED",
            emphasizeValue = sigRegistered && sigState == "connected",
        )
        if (phone != null) MenuRow("PHONE", phone)
        if (deviceId != null && sigRegistered) MenuRow("DEVICE", deviceId.toString())

        if (sigRegistered) {
            MenuRow("REFRESH CONNECTION") {
                scope.launch {
                    PhotonService._signalReceiver?.stop()
                    PhotonService._signalReceiver?.start()
                }
            }
            MenuRow("RESET") {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    PhotonService.resetSignal(context)
                    onReset()
                }
            }
        } else {
            // Not linked: offer the pairing flow directly from settings.
            MenuRow("LINK DEVICE", onClick = onReset)
        }
    }
}

// ─── Chat Settings ──────────────────────────────────────────────

@Composable
private fun ChatPage(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { PhotonPreferences(context) }

    val showThumbnails by prefs.showThumbnails.collectAsState(initial = true)
    val notificationsEnabled by prefs.notificationsEnabled.collectAsState(initial = true)
    val scrollDial by prefs.scrollDial.collectAsState(initial = ScrollDial.DEFAULT)
    val composeStyle by prefs.composeStyle.collectAsState(initial = ComposeStyle.DEFAULT)

    BackHandler { onBack() }
    ChatSettingsContent(
        notificationsEnabled = notificationsEnabled,
        showThumbnails = showThumbnails,
        scrollDial = scrollDial,
        composeStyle = composeStyle,
        onBack = onBack,
        onToggleNotifications = { scope.launch { prefs.setNotificationsEnabled(!notificationsEnabled) } },
        onToggleThumbnails = { scope.launch { prefs.setShowThumbnails(!showThumbnails) } },
        onCycleScrollDial = { scope.launch { prefs.setScrollDial(scrollDial.next()) } },
        onCycleComposeStyle = { scope.launch { prefs.setComposeStyle(composeStyle.next()) } },
    )
}

@Composable
private fun ChatSettingsContent(
    notificationsEnabled: Boolean,
    showThumbnails: Boolean,
    scrollDial: ScrollDial,
    composeStyle: ComposeStyle,
    onBack: () -> Unit,
    onToggleNotifications: () -> Unit,
    onToggleThumbnails: () -> Unit,
    onCycleScrollDial: () -> Unit,
    onCycleComposeStyle: () -> Unit,
) {
    MenuScreen("Chat", onBack) {
        MenuRow("NOTIFICATIONS", if (notificationsEnabled) "ON" else "OFF", onClick = onToggleNotifications)
        MenuRow("THUMBNAILS", if (showThumbnails) "ON" else "OFF", onClick = onToggleThumbnails)
        MenuRow("SCROLL DIAL", scrollDial.label, onClick = onCycleScrollDial)
        MenuRow("COMPOSE", composeStyle.label, onClick = onCycleComposeStyle)
    }
}

// ─── Storage Settings ───────────────────────────────────────────

@Composable
private fun StoragePage(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { PhotonPreferences(context) }
    val bridge = PhotonService._bridgeClient

    val maxMessages by prefs.maxMessages.collectAsState(initial = 50)
    val maxDays by prefs.maxDays.collectAsState(initial = 7)

    BackHandler { onBack() }
    MenuScreen("Storage", onBack) {
        ValueCycleRow("MESSAGES PER CHAT", maxMessages.toString(), listOf(25, 50, 100, 200), maxMessages) { v ->
            scope.launch { prefs.setMaxMessages(v); syncRetention(bridge, v, maxDays) }
        }
        ValueCycleRow("MESSAGE HISTORY", "$maxDays DAYS", listOf(1, 3, 7, 14, 30), maxDays) { v ->
            scope.launch { prefs.setMaxDays(v); syncRetention(bridge, maxMessages, v) }
        }
    }
}

// ─── Shared Components ──────────────────────────────────────────

@Composable
private fun <T> ValueCycleRow(label: String, display: String, options: List<T>, current: T, onSelect: (T) -> Unit) {
    val next = (options.indexOf(current) + 1) % options.size
    MenuRow(label, display) { onSelect(options[next]) }
}

private fun formatState(state: String) = when (state) {
    "connected" -> "CONNECTED"
    "connecting" -> "CONNECTING"
    "logged_out" -> "NOT LINKED"
    "disconnected" -> "DISCONNECTED"
    else -> state.uppercase()
}

private suspend fun syncRetention(bridge: app.photon.service.BridgeClient?, maxMessages: Int, maxDays: Int) {
    try {
        bridge?.request("set_retention", buildJsonObject {
            put("max_messages", maxMessages)
            put("max_days", maxDays)
            put("media_ttl_mins", 5)
        })
    } catch (_: Exception) {}
}

// ─── Previews (LP3 size) ────────────────────────────────────────

@Preview(name = "Settings", widthDp = 360, heightDp = 413, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun SettingsHomePreview() = PhotonTheme {
    SettingsHomeContent(version = "0.4.0", onBack = {}, onNav = {})
}

@Preview(name = "Settings – chat", widthDp = 360, heightDp = 413, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ChatSettingsPreview() = PhotonTheme {
    ChatSettingsContent(
        notificationsEnabled = true,
        showThumbnails = false,
        scrollDial = ScrollDial.MEDIUM,
        composeStyle = ComposeStyle.INLINE,
        onBack = {},
        onToggleNotifications = {},
        onToggleThumbnails = {},
        onCycleScrollDial = {},
        onCycleComposeStyle = {},
    )
}

