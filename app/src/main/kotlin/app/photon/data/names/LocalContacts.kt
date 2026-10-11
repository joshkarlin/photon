package app.photon.data.names

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The LP3's own address book, the first place every platform looks for a
 * person's name (see [DisplayNames]). One per process: [get].
 *
 * Lookups by number are cached, misses included, so a list of unsaved numbers
 * doesn't query the provider on every refresh. Any change to the contacts
 * provider (the LP3 Contacts app, Photon's own editor, an account sync)
 * clears the cache and bumps [version], and the WhatsApp, Signal and SMS name
 * flows re-resolve on it.
 *
 * @param lookup the provider query; replaced in tests
 */
class LocalContacts(
    context: Context,
    lookup: ((String) -> String?)? = null,
) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val lookup: (String) -> String? = lookup ?: { phone -> phoneLookup(resolver, phone) }

    /** A cached lookup; [name] null is a cached miss. */
    private class Cached(val name: String?)

    private val cache = ConcurrentHashMap<String, Cached>()

    private val _version = MutableStateFlow(0L)

    /** Bumped each time the address book changes. */
    val version: StateFlow<Long> = _version

    /** Emits once per address-book change, with no initial value. */
    val changes: Flow<Unit> = version.drop(1).map { }

    // A burst of provider notifications (one edit touches several tables; a
    // sync touches hundreds of rows) becomes one invalidation.
    private val handler = Handler(Looper.getMainLooper())
    private val debouncedInvalidate = Runnable { invalidate() }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(debouncedInvalidate)
            handler.postDelayed(debouncedInvalidate, CHANGE_DEBOUNCE_MS)
        }
    }

    @Volatile private var observing = false

    init {
        startObserving()
    }

    /** The LP3 contact's name for [phone], or null when it isn't saved. */
    fun nameFor(phone: String): String? {
        // Registering needs READ_CONTACTS, which may have been granted since.
        if (!observing) startObserving()
        val key = phone.trim()
        if (key.isEmpty()) return null
        cache[key]?.let { return it.name }
        val before = _version.value
        val name = try {
            lookup(key)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: SecurityException) {
            // No READ_CONTACTS yet: not a miss worth remembering.
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Contact lookup failed: ${e.message}")
            return null
        }
        // An address-book change during the lookup may have made this stale.
        if (_version.value == before) cache[key] = Cached(name)
        return name
    }

    /** Forget every cached lookup and tell the name flows to re-resolve. */
    fun invalidate() {
        cache.clear()
        _version.update { it + 1 }
    }

    @Synchronized
    private fun startObserving() {
        if (observing) return
        try {
            resolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            resolver.registerContentObserver(ContactsContract.Data.CONTENT_URI, true, observer)
            observing = true
        } catch (_: SecurityException) {
            // READ_CONTACTS not granted yet; retried on the next lookup.
        }
    }

    companion object {
        private const val TAG = "LocalContacts"
        internal const val CHANGE_DEBOUNCE_MS = 500L

        @Volatile private var instance: LocalContacts? = null

        fun get(context: Context): LocalContacts =
            instance ?: synchronized(this) {
                instance ?: LocalContacts(context.applicationContext).also { instance = it }
            }

        /** The display name of the contact [phone] belongs to, via the provider's own number matching. */
        internal fun phoneLookup(resolver: ContentResolver, phone: String): String? {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone))
            return resolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }
    }
}
