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
import app.photon.data.KeyboardStyle
import app.photon.data.NotificationLevel
import app.photon.data.PhotonPreferences
import app.photon.data.ScrollDial
import app.photon.service.BridgeClient
import app.photon.service.GoBridge
import app.photon.service.Sessions
import app.photon.ui.shared.components.MenuRow
import app.photon.ui.shared.components.MenuScreen
import app.photon.ui.theme.PhotonTheme
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        MenuRow("Connections") { onNav(Page.CONNECTIONS) }
        MenuRow("Chat") { onNav(Page.CHAT) }
        MenuRow("Storage") { onNav(Page.STORAGE) }
        if (version != null) {
            LightText(
                text = "Photon $version",
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
    val bridge = Sessions.whatsapp?.bridge
    val waState by (bridge?.whatsappState ?: MutableStateFlow("disconnected")).collectAsState()
    val sigState by (Sessions.signal?.receiver?.state ?: MutableStateFlow("disconnected")).collectAsState()
    val sigRegistered = Sessions.signal?.credentials?.isRegistered() == true

    BackHandler { onBack() }
    MenuScreen("Connections", onBack) {
        MenuRow("WhatsApp", formatState(waState)) { onNav(Page.WHATSAPP) }
        MenuRow("Signal", if (sigRegistered) formatState(sigState) else NOT_LINKED) { onNav(Page.SIGNAL) }
    }
}

// ─── WhatsApp Detail ────────────────────────────────────────────

@Composable
private fun WhatsAppPage(onBack: () -> Unit, onReset: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val session = Sessions.whatsapp
    val bridge = session?.bridge
    val waState by (bridge?.whatsappState ?: MutableStateFlow("disconnected")).collectAsState()

    BackHandler { onBack() }
    MenuScreen("WhatsApp", onBack) {
        MenuRow("Status", formatState(waState), emphasizeValue = waState == "connected")

        if (waState == "logged_out") {
            // Not paired: the only useful action is to start the pairing flow.
            MenuRow("Link Device", onClick = onReset)
        } else {
            MenuRow("Refresh Connection", onClick = {
                scope.launch {
                    try {
                        bridge?.reconnectWhatsApp()
                    } catch (_: Exception) {}
                }
            })
            MenuRow("Reset", onClick = {
                scope.launch {
                    session?.reset()
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
    val session = Sessions.signal
    val sigState by (session?.receiver?.state ?: MutableStateFlow("disconnected")).collectAsState()
    val sigRegistered = session?.credentials?.isRegistered() == true
    val phone = session?.credentials?.phoneNumber
    val deviceId = session?.credentials?.storedDeviceId

    BackHandler { onBack() }
    MenuScreen("Signal", onBack) {
        MenuRow(
            "Status",
            if (sigRegistered) formatState(sigState) else NOT_LINKED,
            emphasizeValue = sigRegistered && sigState == "connected",
        )
        if (phone != null) MenuRow("Phone", phone)
        if (deviceId != null && sigRegistered) MenuRow("Device", deviceId.toString())

        if (sigRegistered) {
            MenuRow("Refresh Connection") {
                scope.launch {
                    session?.refresh()
                }
            }
            MenuRow("Reset") {
                scope.launch {
                    // Unlinking waits on the network; navigating must happen on the main thread.
                    withContext(Dispatchers.IO) { session?.reset() }
                    onReset()
                }
            }
        } else {
            // Not linked: offer the pairing flow directly from settings.
            MenuRow("Link Device", onClick = onReset)
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
    val notificationLevel by prefs.notificationLevel.collectAsState(initial = NotificationLevel.DEFAULT)
    val scrollDial by prefs.scrollDial.collectAsState(initial = ScrollDial.DEFAULT)
    val composeStyle by prefs.composeStyle.collectAsState(initial = ComposeStyle.DEFAULT)
    val keyboardStyle by prefs.keyboardStyle.collectAsState(initial = KeyboardStyle.DEFAULT)

    BackHandler { onBack() }
    ChatSettingsContent(
        notificationLevel = notificationLevel,
        showThumbnails = showThumbnails,
        scrollDial = scrollDial,
        composeStyle = composeStyle,
        keyboardStyle = keyboardStyle,
        onBack = onBack,
        onCycleNotifications = { scope.launch { prefs.setNotificationLevel(notificationLevel.next()) } },
        onToggleThumbnails = { scope.launch { prefs.setShowThumbnails(!showThumbnails) } },
        onCycleScrollDial = { scope.launch { prefs.setScrollDial(scrollDial.next()) } },
        onCycleComposeStyle = { scope.launch { prefs.setComposeStyle(composeStyle.next()) } },
        onCycleKeyboardStyle = { scope.launch { prefs.setKeyboardStyle(keyboardStyle.next()) } },
    )
}

@Composable
private fun ChatSettingsContent(
    notificationLevel: NotificationLevel,
    showThumbnails: Boolean,
    scrollDial: ScrollDial,
    composeStyle: ComposeStyle,
    keyboardStyle: KeyboardStyle,
    onBack: () -> Unit,
    onCycleNotifications: () -> Unit,
    onToggleThumbnails: () -> Unit,
    onCycleScrollDial: () -> Unit,
    onCycleComposeStyle: () -> Unit,
    onCycleKeyboardStyle: () -> Unit,
) {
    MenuScreen("Chat", onBack) {
        MenuRow("Notifications", sentenceCase(notificationLevel.label), onClick = onCycleNotifications)
        MenuRow("Thumbnails", if (showThumbnails) "On" else "Off", onClick = onToggleThumbnails)
        MenuRow("Scroll Dial", sentenceCase(scrollDial.label), onClick = onCycleScrollDial)
        MenuRow("Compose", sentenceCase(composeStyle.label), onClick = onCycleComposeStyle)
        MenuRow("Keyboard", sentenceCase(keyboardStyle.label), onClick = onCycleKeyboardStyle)
    }
}

// ─── Storage Settings ───────────────────────────────────────────

@Composable
private fun StoragePage(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { PhotonPreferences(context) }
    val bridge = Sessions.whatsapp?.bridge

    val maxMessages by prefs.maxMessages.collectAsState(initial = 50)
    val maxDays by prefs.maxDays.collectAsState(initial = 7)

    BackHandler { onBack() }
    MenuScreen("Storage", onBack) {
        ValueCycleRow("Messages per Chat", maxMessages.toString(), listOf(25, 50, 100, 200), maxMessages) { v ->
            scope.launch { prefs.setMaxMessages(v); syncRetention(bridge, v, maxDays) }
        }
        ValueCycleRow("Message History", if (maxDays == 1) "1 day" else "$maxDays days", listOf(1, 3, 7, 14, 30), maxDays) { v ->
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

private const val NOT_LINKED = "Not linked"

/** A connection state as a setting's value. */
private fun formatState(state: String) = when (state) {
    "logged_out" -> NOT_LINKED
    else -> sentenceCase(state.replace('_', ' '))
}

/**
 * A setting's value in sentence case, as LightOS shows values ("Full
 * screen"); the preference enums name theirs in capitals.
 */
internal fun sentenceCase(label: String): String = label.lowercase().replaceFirstChar { it.titlecase() }

private suspend fun syncRetention(bridge: BridgeClient?, maxMessages: Int, maxDays: Int) {
    try {
        bridge?.request("set_retention", buildJsonObject {
            put("max_messages", maxMessages)
            put("max_days", maxDays)
            put("media_ttl_mins", GoBridge.MEDIA_TTL_MINS)
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
        notificationLevel = NotificationLevel.VIBRATE,
        showThumbnails = false,
        scrollDial = ScrollDial.MEDIUM,
        composeStyle = ComposeStyle.INLINE,
        keyboardStyle = KeyboardStyle.SYSTEM,
        onBack = {},
        onCycleNotifications = {},
        onToggleThumbnails = {},
        onCycleScrollDial = {},
        onCycleComposeStyle = {},
        onCycleKeyboardStyle = {},
    )
}

