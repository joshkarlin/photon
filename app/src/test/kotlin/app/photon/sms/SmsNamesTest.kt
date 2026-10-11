package app.photon.sms

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import app.photon.data.model.Conversation
import app.photon.data.names.DisplayNames
import app.photon.data.names.LocalContacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** SMS chat names: LP3 contact, else the formatted number; senders' own names as sent. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SmsNamesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val book = mutableMapOf("+447700900123" to "Mum")
    private val contacts = LocalContacts(context) { book[it] }
    private var savedCountry = ""

    @Before
    fun setUp() {
        savedCountry = DisplayNames.defaultCountry
        DisplayNames.defaultCountry = "GB"
        Robolectric.buildContentProvider(FakeSmsProvider::class.java).create("sms")
    }

    @After
    fun tearDown() {
        scope.cancel()
        DisplayNames.defaultCountry = savedCountry
    }

    private fun names(list: List<Conversation>) = list.associate { it.jid to it.name }

    @Test
    fun namesFollowThePolicyAndFollowEditsInTheLp3AddressBook() {
        val repo = SmsRepository(context, scope, contacts)
        val emitted = Channel<List<Conversation>>(Channel.UNLIMITED)
        scope.launch { repo.conversations.filterNotNull().collect { emitted.send(it) } }

        val first = runBlocking { withTimeout(5_000) { emitted.receive() } }
        assertEquals(
            mapOf("+447700900123" to "Mum", "+447700900999" to "+44 7700 900999", "HSBC UK" to "HSBC UK"),
            names(first),
        )

        // Saved in the LP3 Contacts app: no restart, no new SMS needed.
        book["+447700900999"] = "Dad"
        contacts.invalidate()

        val second = runBlocking { withTimeout(5_000) { emitted.receive() } }
        assertEquals("Dad", names(second)["+447700900999"])
    }

    /** Three senders, one message each. */
    class FakeSmsProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ, Telephony.Sms.TYPE),
        ).apply {
            addRow(arrayOf<Any>("+44 7700 900123", "Dinner?", 3_000L, 1, Telephony.Sms.MESSAGE_TYPE_INBOX))
            addRow(arrayOf<Any>("+447700900999", "It's Dad", 2_000L, 1, Telephony.Sms.MESSAGE_TYPE_INBOX))
            addRow(arrayOf<Any>("HSBC UK", "Your code", 1_000L, 1, Telephony.Sms.MESSAGE_TYPE_INBOX))
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
