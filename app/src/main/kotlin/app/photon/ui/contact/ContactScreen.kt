package app.photon.ui.contact

import android.content.ContentProviderOperation
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Header
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 12.dp)) {
            Text("<", fontSize = 18.sp, color = Color(0xFF666666),
                modifier = Modifier.align(Alignment.CenterStart).clickable(onClick = onBack).padding(end = 16.dp))
            Text("CONTACT", fontSize = 13.sp, letterSpacing = 3.sp, color = Color(0xFF666666),
                modifier = Modifier.align(Alignment.Center))
        }
        HorizontalDivider(color = Color(0xFF1A1A1A))

        // Phone number (read-only display).
        Spacer(Modifier.height(28.dp))
        Text(
            text = phone,
            fontSize = 24.sp,
            color = Color.White,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(28.dp))
        HorizontalDivider(color = Color(0xFF1A1A1A))

        // Name field — editable. Pre-filled with existing name if the number
        // is already saved, otherwise with whatever display name we knew.
        Text(
            text = "NAME",
            fontSize = 9.sp, letterSpacing = 2.sp, color = Color(0xFF666666),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 18.dp),
        ) {
            BasicTextField(
                value = nameInput,
                onValueChange = { nameInput = it; status = null },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 18.sp, letterSpacing = 1.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (nameInput.isEmpty()) {
                Text(
                    "(unsaved)", fontSize = 18.sp, color = Color(0xFF444444),
                )
            }
        }
        HorizontalDivider(color = Color(0xFF1A1A1A))

        // Save action.
        val saveLabel = when {
            saving -> "SAVING..."
            existingLookupUri != null -> "UPDATE CONTACT"
            else -> "SAVE TO CONTACTS"
        }
        val canSave = !saving && nameInput.isNotBlank()
        Text(
            text = saveLabel,
            fontSize = 18.sp, letterSpacing = 2.sp,
            color = if (canSave) Color.White else Color(0xFF444444),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = canSave) {
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
                .padding(horizontal = 20.dp, vertical = 22.dp),
        )
        HorizontalDivider(color = Color(0xFF111111))

        status?.let {
            Text(
                text = it.uppercase(),
                fontSize = 11.sp, letterSpacing = 2.sp, color = Color(0xFF666666),
                modifier = Modifier.fillMaxWidth().padding(20.dp),
            )
        }
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
