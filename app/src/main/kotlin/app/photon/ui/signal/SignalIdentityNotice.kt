package app.photon.ui.signal

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.photon.signal.store.ContactIdentity
import app.photon.signal.store.IdentityVerification
import app.photon.signal.store.PhotonIdentityKeyStore
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
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val name = contacts[first.address] ?: "Contact"
            Text(
                text = if (changed.size == 1) "Safety number changed: $name"
                    else "Safety numbers changed: ${changed.size} contacts",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { reviewing = first }) {
                Text("REVIEW", color = Color.White, fontSize = 11.sp)
            }
        }
        HorizontalDivider(color = Color(0xFF1A1A1A))
    }

    reviewing?.let { identity ->
        val name = contacts[identity.address] ?: "This contact"
        AlertDialog(
            onDismissRequest = { reviewing = null },
            containerColor = Color(0xFF0D0D0D),
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(4.dp),
            title = { Text("Safety number changed", color = Color.White, fontSize = 18.sp) },
            text = {
                val warning = if (identity.verification == IdentityVerification.UNVERIFIED)
                    "Sending is blocked because the previous safety number was verified. " else ""
                Text(
                    "$name has a new safety number. ${warning}Check it on your main Signal device. " +
                        "Accepting allows sending and leaves this contact unverified.",
                    color = Color.White, fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { store.acceptChange(identity) }
                        reviewing = null
                    }
                }) { Text("ACCEPT CHANGE", color = Color.White, fontSize = 12.sp) }
            },
            dismissButton = {
                TextButton(onClick = { reviewing = null }) {
                    Text("CANCEL", color = Color(0xFF666666), fontSize = 12.sp)
                }
            },
        )
    }
}
