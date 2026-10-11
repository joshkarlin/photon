package app.photon.ui.signal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.photon.data.model.Platform
import app.photon.data.names.DisplayNames
import app.photon.signal.store.ContactIdentity
import app.photon.signal.store.IdentityVerification
import app.photon.signal.store.PhotonIdentityKeyStore
import app.photon.ui.shared.components.backButton
import app.photon.ui.theme.PhotonGrid
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SignalIdentityNotice(store: PhotonIdentityKeyStore, contacts: Map<String, String>) {
    val revision by store.changes.collectAsState()
    val changed = remember(revision, contacts) {
        contacts.keys.mapNotNull(store::getRecord).filter { it.changePending }
    }
    var reviewing by remember { mutableStateOf<ContactIdentity?>(null) }
    val scope = rememberCoroutineScope()

    changed.firstOrNull()?.let { first ->
        val name = contacts[first.address] ?: DisplayNames.placeholder(Platform.SIGNAL)
        // The whole notice opens the review; "Review" is underlined, as LightOS marks a link.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable { reviewing = first }
                .padding(horizontal = PhotonGrid.CHAT_MARGIN.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
            horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = if (changed.size == 1) "Safety number changed: $name"
                    else "Safety numbers changed: ${changed.size} contacts",
                variant = LightTextVariant.Detail,
                modifier = Modifier.weight(1f),
            )
            LightText(text = "Review", variant = LightTextVariant.Detail, underline = true)
        }
    }

    reviewing?.let { identity ->
        val name = contacts[identity.address] ?: DisplayNames.placeholder(Platform.SIGNAL)
        val warning = if (identity.verification == IdentityVerification.UNVERIFIED)
            "Sending is blocked because the previous safety number was verified. " else ""
        // A full-screen page over the thread. The chat's header slot can't
        // hold one, so it gets a window of its own, without a dialog's look.
        Dialog(
            onDismissRequest = { reviewing = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect {
                // LightOS pages change without transitions, and nothing behind shows.
                window?.setWindowAnimations(0)
                window?.setDimAmount(0f)
            }
            SafetyNumberPage(
                text = "$name has a new safety number. ${warning}Check it on your main Signal device. " +
                    "Accepting allows sending and leaves this contact unverified.",
                onAccept = {
                    scope.launch {
                        withContext(Dispatchers.IO) { store.acceptChange(identity) }
                        reviewing = null
                    }
                },
                onBack = { reviewing = null },
            )
        }
    }
}

/** A changed safety number, explained, with accepting it in the bottom bar. */
@Composable
private fun SafetyNumberPage(text: String, onAccept: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(leftButton = backButton(onBack), center = LightTopBarCenter.Text("Safety Number"))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = PhotonGrid.MARGIN.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(text = text, variant = LightTextVariant.Copy, align = TextAlign.Center)
        }
        LightBottomBar(items = listOf(LightBarButton.Text(text = "ACCEPT CHANGE", onClick = onAccept)))
    }
}
