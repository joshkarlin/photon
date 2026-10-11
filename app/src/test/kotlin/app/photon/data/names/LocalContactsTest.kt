package app.photon.data.names

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The LP3 address book: cached lookups, and invalidation when it changes. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LocalContactsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val book = mutableMapOf("+447700900123" to "Mum")
    private var lookups = 0
    private lateinit var contacts: LocalContacts

    @Before
    fun setUp() {
        grantReadContacts()
        contacts = LocalContacts(context) { phone ->
            lookups++
            book[phone]
        }
    }

    private fun grantReadContacts() =
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CONTACTS)

    private fun waitOutDebounce() =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LocalContacts.CHANGE_DEBOUNCE_MS + 100))

    @Test
    fun hitsAndMissesAreBothCached() {
        assertEquals("Mum", contacts.nameFor("+447700900123"))
        assertEquals("Mum", contacts.nameFor("+447700900123"))
        assertNull(contacts.nameFor("+447700900999"))
        assertNull(contacts.nameFor("+447700900999"))
        assertEquals(2, lookups)
    }

    @Test
    fun invalidatingForgetsLookupsAndBumpsTheVersion() {
        assertNull(contacts.nameFor("+447700900999"))
        book["+447700900999"] = "Dad"

        contacts.invalidate()

        assertEquals(1L, contacts.version.value)
        assertEquals("Dad", contacts.nameFor("+447700900999"))
    }

    /** An edit in the LP3 Contacts app reaches every platform without a restart. */
    @Test
    fun aContactsProviderChangeInvalidatesOnceTheBurstIsOver() {
        assertEquals("Mum", contacts.nameFor("+447700900123"))
        book["+447700900123"] = "Mother"

        context.contentResolver.notifyChange(ContactsContract.Contacts.CONTENT_URI, null)
        context.contentResolver.notifyChange(ContactsContract.Data.CONTENT_URI, null)
        context.contentResolver.notifyChange(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, "7"), null)
        assertEquals(0L, contacts.version.value)
        waitOutDebounce()

        assertEquals(1L, contacts.version.value)
        assertEquals("Mother", contacts.nameFor("+447700900123"))
    }

    @Test
    fun unrelatedProviderChangesAreIgnored() {
        context.contentResolver.notifyChange(Uri.parse("content://sms"), null)
        waitOutDebounce()
        assertEquals(0L, contacts.version.value)
    }

    /** No READ_CONTACTS yet: the miss isn't remembered, so it resolves once granted. */
    @Test
    fun aDeniedLookupIsNotCachedAsAMiss() {
        var granted = false
        val guarded = LocalContacts(context) { phone ->
            if (!granted) throw SecurityException("READ_CONTACTS")
            book[phone]
        }
        assertNull(guarded.nameFor("+447700900123"))
        granted = true
        assertEquals("Mum", guarded.nameFor("+447700900123"))
    }

    /** Contacts permission granted after start: the next lookup resolves, and everything re-resolves. */
    @Test
    fun withoutReadContactsNothingResolvesUntilItIsGranted() {
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create(ContactsContract.AUTHORITY)
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CONTACTS)
        val real = LocalContacts(context)
        assertNull(real.nameFor("+447700900123"))
        assertEquals(0L, real.version.value)

        grantReadContacts()

        assertEquals("Mum", real.nameFor("+447700900123"))
        assertEquals(1L, real.version.value)
    }

    @Test
    fun theDefaultLookupAsksThePhoneLookupProvider() {
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create(ContactsContract.AUTHORITY)
        val real = LocalContacts(context)
        assertEquals("Mum", real.nameFor("+447700900123"))
        assertNull(real.nameFor("+447700900999"))
    }

    /** Answers PhoneLookup queries from a one-entry address book. */
    class FakeContactsProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)).apply {
            if (uri.lastPathSegment == "+447700900123") addRow(arrayOf("Mum"))
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
