package app.photon.ui.contact

import android.content.ContentProviderOperation
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.photon.ui.shared.components.LightInputField
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Photon's own contact editor. Writes directly to ContactsContract so the
 * saved record shows up in LightOS contacts (and any other app reading the
 * system's contacts provider). We don't hand off to the AOSP Contacts
 * editor because LightOS doesn't expose its own equivalent, and an AOSP
 * handoff visually breaks the LP3 flow.
 */
@Composable
fun ContactScreen(
    phone: String,
    displayName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var existingLookupUri by remember { mutableStateOf<Uri?>(null) }
    var existingName by remember { mutableStateOf<String?>(null) }
    var nameInput by remember { mutableStateOf(if (displayName.isNotBlank() && displayName != phone) displayName else "") }
    var saving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    // Look up existing contact for this number on first composition.
    LaunchedEffect(phone) {
        withContext(Dispatchers.IO) {
            try {
                val lookupUri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(phone),
                )
                context.contentResolver.query(
                    lookupUri,
                    arrayOf(
                        ContactsContract.PhoneLookup._ID,
                        ContactsContract.PhoneLookup.LOOKUP_KEY,
                        ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ),
                    null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        existingName = c.getString(2)
                        nameInput = c.getString(2) ?: nameInput
                        existingLookupUri = ContactsContract.Contacts.getLookupUri(
                            c.getLong(0), c.getString(1),
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun save() {
        saving = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    writeContact(
                        context.contentResolver,
                        name = nameInput.trim(),
                        phone = phone,
                        existingLookupUri = existingLookupUri,
                    )
                    true
                } catch (e: Exception) {
                    android.util.Log.w("ContactScreen", "Save failed", e)
                    false
                }
            }
            if (ok) {
                // Invalidate cached name lookups so the chat list
                // and titles pick up the new contact immediately
                // instead of after the next reconnect / refresh.
                app.photon.service.PhotonService._signalReceiver?.refreshContactNames()
                app.photon.service.PhotonService._smsRepository?.refreshContactNames()
            }
            saving = false
            status = if (ok) "Saved." else "Save failed — check permissions."
            if (ok) onBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(leftButton = backButton(onBack), center = LightTopBarCenter.Text("Contact"))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = PhotonGrid.MENU_MARGIN.gridUnitsAsDp()),
            verticalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
        ) {
            // Phone number (read-only display).
            LightText(
                text = phone,
                variant = LightTextVariant.Heading,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )

            // Name field — editable. Pre-filled with existing name if the number
            // is already saved, otherwise with whatever display name we knew.
            Column {
                LightText("NAME", variant = LightTextVariant.Detail, lighten = true)
                LightInputField(
                    value = nameInput,
                    onValueChange = { nameInput = it; status = null },
                    placeholder = "(unsaved)",
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done,
                    ),
                )
            }

            status?.let {
                LightText(text = it, variant = LightTextVariant.Detail, lighten = true)
            }
        }

        // Save is the page's primary action; it only appears once there's a
        // name to save.
        val canSave = !saving && nameInput.isNotBlank()
        LightBottomBar(
            items = when {
                saving -> listOf(LightBarButton.Text(text = "SAVING…", onClick = null))
                canSave -> listOf(
                    LightBarButton.Text(
                        text = if (existingLookupUri != null) "UPDATE CONTACT" else "SAVE TO CONTACTS",
                        onClick = ::save,
                    ),
                )
                else -> emptyList()
            },
        )
    }
}

/**
 * Insert (or update) a system contact record. We use applyBatch so the
 * RawContacts + StructuredName + Phone rows are committed atomically.
 */
private fun writeContact(
    resolver: android.content.ContentResolver,
    name: String,
    phone: String,
    existingLookupUri: Uri?,
) {
    if (existingLookupUri != null) {
        // Update the existing contact's display name. Phone is already
        // associated with this contact (that's how we matched it).
        val contactId = ContactsContract.Contacts.lookupContact(resolver, existingLookupUri)?.lastPathSegment?.toLongOrNull()
        if (contactId != null) {
            // Find the StructuredName row for the contact's first raw contact.
            val rawContactsCursor = resolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null,
            )
            val rawContactId = rawContactsCursor?.use {
                if (it.moveToFirst()) it.getLong(0) else null
            }
            if (rawContactId != null) {
                val ops = arrayListOf<ContentProviderOperation>()
                ops.add(
                    ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                        .withSelection(
                            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                            arrayOf(
                                rawContactId.toString(),
                                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                            ),
                        )
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                        .build(),
                )
                resolver.applyBatch(ContactsContract.AUTHORITY, ops)
                return
            }
        }
        // Fall through to insert if the update path didn't find a row.
    }

    val ops = arrayListOf<ContentProviderOperation>()
    ops.add(
        ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build(),
    )
    ops.add(
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            )
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .build(),
    )
    ops.add(
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            )
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
            .withValue(
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
            )
            .build(),
    )
    resolver.applyBatch(ContactsContract.AUTHORITY, ops)
}
