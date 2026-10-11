package app.photon.ui.signal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import app.photon.signal.store.ContactIdentity
import app.photon.signal.store.IdentityVerification
import app.photon.signal.store.PhotonIdentityKeyStore
import app.photon.ui.theme.PhotonGrid
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PhotonGrid.CHAT_MARGIN.gridUnitsAsDp()),
            horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val name = contacts[first.address] ?: "Contact"
            LightText(
                text = if (changed.size == 1) "Safety number changed: $name"
                    else "Safety numbers changed: ${changed.size} contacts",
                variant = LightTextVariant.Detail,
                modifier = Modifier.weight(1f),
            )
            LightText(
                text = "REVIEW",
                variant = LightTextVariant.Detail,
                modifier = Modifier
                    .lightClickable { reviewing = first }
                    .padding(vertical = 0.5f.gridUnitsAsDp()),
            )
        }
    }

    reviewing?.let { identity ->
        val name = contacts[identity.address] ?: "This contact"
        AlertDialog(
            onDismissRequest = { reviewing = null },
            containerColor = LightThemeTokens.colors.background,
            tonalElevation = 0.dp,
            shape = RectangleShape,
            title = { LightText("Safety number changed", variant = LightTextVariant.Paragraph) },
            text = {
                val warning = if (identity.verification == IdentityVerification.UNVERIFIED)
                    "Sending is blocked because the previous safety number was verified. " else ""
                LightText(
                    "$name has a new safety number. ${warning}Check it on your main Signal device. " +
                        "Accepting allows sending and leaves this contact unverified.",
                    variant = LightTextVariant.Detail,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { store.acceptChange(identity) }
                        reviewing = null
                    }
                }) { LightText("ACCEPT CHANGE", variant = LightTextVariant.Detail) }
            },
            dismissButton = {
                TextButton(onClick = { reviewing = null }) {
                    LightText("CANCEL", variant = LightTextVariant.Detail, lighten = true)
                }
            },
        )
    }
}
