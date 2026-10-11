package app.photon.signal.receiver

import app.photon.signal.db.getContact
import app.photon.signal.db.getConversations
import app.photon.signal.db.updateContactPhone
import app.photon.signal.db.updateContactProfileName
import java.util.Optional
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.api.messages.multidevice.DeviceContact
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The primary's contact sync supplies each ACI's number and address-book
 * name: a name source below the LP3 contact and above the Signal profile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ContactsSyncTest {

    private val mum = "00000000-0000-4000-8000-0000000000a1"
    private val mumPhone = "+447700900123"

    private lateinit var fx: ReceiverFixture
    private lateinit var sync: ContactsSync

    @Before
    fun setUp() {
        fx = ReceiverFixture("00000000-0000-4000-8000-00000000000a")
        sync = ContactsSync(fx.context, fx.db, pushSocket = { null }, onSynced = {})
    }

    @After
    fun tearDown() = fx.close()

    private fun deviceContact(aci: String, phone: String?, name: String?) = DeviceContact(
        Optional.of(ServiceId.ACI.parseOrNull(aci)!!), Optional.ofNullable(phone), Optional.ofNullable(name),
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
    )

    @Test
    fun theAddressBookNameRanksBetweenTheLp3ContactAndTheProfile() {
        fx.db.updateContactProfileName(mum, "Jane")
        sync.ingestDeviceContact(deviceContact(mum, mumPhone, "Mother"))

        assertEquals(mumPhone, fx.db.getContact(mum)?.phone)
        assertEquals("Mother", fx.names.person(mum))

        fx.addressBook[mumPhone] = "Mum"
        assertEquals("Mum", fx.names.person(mum))
    }

    /** Ingesting the address book used to create a message-less DM row per contact. */
    @Test
    fun syncingContactsCreatesNoConversations() {
        sync.ingestDeviceContact(deviceContact(mum, mumPhone, "Mother"))
        assertEquals(emptyList(), fx.db.getConversations())
    }

    /** Each sync carries the whole entry: a name removed on the primary goes; a missing number doesn't. */
    @Test
    fun aLaterSyncReplacesTheNameButKeepsAKnownNumber() {
        fx.db.updateContactPhone(mum, mumPhone)
        sync.ingestDeviceContact(deviceContact(mum, mumPhone, "Mother"))
        sync.ingestDeviceContact(deviceContact(mum, null, null))

        assertNull(fx.db.getContact(mum)?.name)
        assertEquals(mumPhone, fx.db.getContact(mum)?.phone)
    }
}
