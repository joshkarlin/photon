package app.photon.data.names

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * Without READ_CONTACTS nothing resolves (and nothing is cached); once it's
 * granted, the next lookup starts observing and re-resolves every name.
 *
 * @param lookup the provider query; replaced in tests
 */
class LocalContacts(
    context: Context,
    lookup: ((String) -> String?)? = null,
) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val lookup: (String) -> String? = lookup ?: { phone ->
        if (!canRead()) throw SecurityException("READ_CONTACTS not granted")
        phoneLookup(resolver, phone)
    }

    /** A cached lookup; [name] null is a cached miss. */
    private class Cached(val name: String?)

    private val cache = ConcurrentHashMap<String, Cached>()

    private val _version = MutableStateFlow(0L)

    /** Bumped each time the address book changes. */
    val version: StateFlow<Long> = _version

    /**
     * A refresh trigger: emits at once, then after each address-book change.
     * Emitting at once (rather than skipping the current version) means a
     * change made while a collector is still subscribing can't be missed.
     */
    val changes: Flow<Unit> = version.map { }

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
    @Volatile private var wasDenied = false

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

    /**
     * Every LP3 contact with a number: their name and numbers as saved, the
     * default number first, then mobiles. Empty without READ_CONTACTS. It
     * reads the provider, so call it off the main thread; [changes] says
     * when to read again.
     */
    fun all(): List<LocalContact> {
        if (!observing) startObserving()
        if (!canRead()) return emptyList()
        return try {
            allContacts(resolver)
        } catch (_: SecurityException) {
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Reading contacts failed: ${e.message}")
            emptyList()
        }
    }

    /** Forget every cached lookup and tell the name flows to re-resolve. */
    fun invalidate() {
        cache.clear()
        _version.update { it + 1 }
    }

    private fun canRead(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    private fun startObserving() {
        if (observing) return
        try {
            // Registering for a provider the app can't read throws.
            if (!canRead()) throw SecurityException("READ_CONTACTS not granted")
            resolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            resolver.registerContentObserver(ContactsContract.Data.CONTENT_URI, true, observer)
            observing = true
        } catch (_: SecurityException) {
            // Not granted yet; retried on the next lookup.
            wasDenied = true
            return
        }
        // Just granted: names resolved without the address book re-resolve.
        if (wasDenied) invalidate()
    }

    companion object {
        private const val TAG = "LocalContacts"
        internal const val CHANGE_DEBOUNCE_MS = 500L

        @Volatile private var instance: LocalContacts? = null

        fun get(context: Context): LocalContacts =
            instance ?: synchronized(this) {
                instance ?: LocalContacts(context.applicationContext).also { instance = it }
            }

        /** Every contact with a number, as [all] describes. */
        internal fun allContacts(resolver: ContentResolver): List<LocalContact> {
            class Number(val number: String, val rank: Int)
            val names = LinkedHashMap<Long, String>()
            val numbers = HashMap<Long, MutableList<Number>>()
            resolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.IS_SUPER_PRIMARY, Phone.TYPE),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(2)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val id = c.getLong(0)
                    names.putIfAbsent(id, c.getString(1)?.trim().orEmpty())
                    val rank = when {
                        c.getInt(3) != 0 -> 0
                        c.getInt(4) == Phone.TYPE_MOBILE -> 1
                        else -> 2
                    }
                    numbers.getOrPut(id) { mutableListOf() } += Number(number, rank)
                }
            }
            return names.map { (id, name) ->
                // The same number saved twice ("07400 123456", "+44 7400 123456") once.
                val phones = numbers[id].orEmpty().sortedBy { it.rank }.map { it.number }
                    .distinctBy { PhoneNumbers.e164(it) ?: it.filter(Char::isDigit) }
                LocalContact(name.ifEmpty { DisplayNames.number(phones.first()) }, phones)
            }
        }

        /** The display name of the contact [phone] belongs to, via the provider's own number matching. */
        internal fun phoneLookup(resolver: ContentResolver, phone: String): String? {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone))
            return resolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }
    }
}

/** An LP3 contact: [name] and the [phones] saved for them, as written. */
data class LocalContact(val name: String, val phones: List<String>)
