package app.photon.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.photon.data.db.PhotonDatabase
import app.photon.data.names.DisplayNames
import app.photon.data.names.LocalContacts
import app.photon.data.repository.ChatRepository
import app.photon.service.BridgeClient
import app.photon.service.GoBridge
import app.photon.signal.SignalRepository
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.updateContactFromSync
import app.photon.signal.db.updateContactProfileName
import app.photon.signal.store.PhotonIdentityKeyStore
import app.photon.signal.store.SignalProtocolDatabase
import app.photon.sms.SmsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** A chat with no conversation yet is titled as the person, by each platform's names. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class NewChatTitleTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val book = mapOf("+447700900123" to "Mo Salah")
    private val contacts = LocalContacts(context) { book[it] }
    private val closeables = mutableListOf<AutoCloseable>()
    private var savedCountry = ""

    @Before
    fun setCountry() {
        savedCountry = DisplayNames.defaultCountry
        DisplayNames.defaultCountry = "GB"
    }

    @After
    fun tearDown() {
        DisplayNames.defaultCountry = savedCountry
        closeables.forEach { it.close() }
        scope.cancel()
    }

    @Test
    fun whatsAppNamesTheLp3ContactElseTheNumber() {
        val bridge = BridgeClient(GoBridge({ null }))
        val backend = WhatsAppBackend(ChatRepository(PhotonDatabase(context), bridge, scope, contacts), bridge, scope)
        assertEquals("Mo Salah", backend.newChatTitle("447700900123@s.whatsapp.net"))
        assertEquals("+44 7700 900999", backend.newChatTitle("447700900999@s.whatsapp.net"))
    }

    @Test
    fun signalNamesWhatItsContactsRowAllows() {
        val db = SignalMessageDatabase(context).also { closeables += it }
        val backend = SignalBackend(
            SignalRepository(db, null, scope, contacts) { null },
            MutableStateFlow("connected"),
            PhotonIdentityKeyStore(SignalProtocolDatabase(context).also { closeables += it }),
        ) { null }
        val mo = "00000000-0000-4000-8000-0000000000a1"
        val synced = "00000000-0000-4000-8000-0000000000a2"
        val profiled = "00000000-0000-4000-8000-0000000000a3"
        db.updateContactFromSync(mo, "+447700900123", "Mo")
        db.updateContactFromSync(synced, "+447700900456", "Sam")
        db.updateContactProfileName(profiled, "Profile Pat")

        assertEquals("Mo Salah", backend.newChatTitle(mo), "the LP3 contact first")
        assertEquals("Sam", backend.newChatTitle(synced), "then the primary's contact sync")
        assertEquals("Profile Pat", backend.newChatTitle(profiled))
        assertEquals("Signal contact", backend.newChatTitle("00000000-0000-4000-8000-0000000000ff"))
    }

    @Test
    fun smsNamesTheLp3ContactElseTheNumber() {
        val backend = SmsBackend(SmsRepository(context, scope, contacts))
        assertEquals("Mo Salah", backend.newChatTitle("+447700900123"))
        assertEquals("+44 7700 900999", backend.newChatTitle("+447700900999"))
    }
}
