package app.photon.signal

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a contact's display name by phone number via the system contacts
 * provider. Used as a fallback when Signal's encrypted profile name isn't
 * available — e.g. because the stored profile key is stale and the server
 * won't release the encrypted name to us.
 *
 * Caches results in-memory; an unmatched number is also cached (as null) to
 * avoid repeatedly hitting the provider for the same lookup.
 */
class AndroidContactResolver(private val context: Context) {
    companion object { private const val TAG = "AndroidContactResolver" }

    private val cache = ConcurrentHashMap<String, String>()
    private val unmatched = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Clear all cached results so subsequent lookups re-query the provider. */
    fun invalidate() {
        cache.clear()
        unmatched.clear()
    }

    fun resolve(phoneNumber: String): String? {
        if (phoneNumber.isBlank()) return null
        cache[phoneNumber]?.let { return it }
        if (phoneNumber in unmatched) return null

        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber),
            )
            val name = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
            if (name != null) {
                Log.i(TAG, "Resolved $phoneNumber -> $name")
                cache[phoneNumber] = name
            } else {
                Log.d(TAG, "No contact found for $phoneNumber")
                unmatched.add(phoneNumber)
            }
            name
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS denied: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Lookup failed for $phoneNumber: ${e.message}")
            null
        }
    }
}
