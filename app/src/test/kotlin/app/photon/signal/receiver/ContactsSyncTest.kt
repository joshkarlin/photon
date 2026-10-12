package app.photon.signal.receiver

import app.photon.signal.ChatTimer
import app.photon.signal.db.chatTimer
import app.photon.signal.db.getContact
import app.photon.signal.db.getConversations
import app.photon.signal.db.getMessages
import app.photon.signal.db.updateContactPhone
import app.photon.signal.db.updateContactProfileName
import app.photon.signal.db.updateDmTimer
import app.photon.signal.db.upsertConversation
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

    /**
     * The primary's timer for each person: a chat Photon has a row for
     * takes it (by the transcript rule, with no line), and one it starts
     * later begins with it. A timer without a version can't be ordered
     * against what messages said, so it's ignored.
     */
    /** Signal-Android leaves the timer out when it's off: a version alone is "off at that version". */
    @Test
    fun aVersionWithoutATimerIsOff() {
        fx.db.upsertConversation(jid = mum, name = null, isGroup = false)
        fx.db.updateDmTimer(mum, 0) { ChatTimer(604_800, 2) }

        sync.ingestDeviceContact(
            DeviceContact(
                Optional.of(ServiceId.ACI.parseOrNull(mum)!!), Optional.of(mumPhone), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(3), Optional.empty(),
            ),
        )

        assertEquals(ChatTimer(0, 3), fx.db.chatTimer(mum))
    }

    @Test
    fun theDisappearingTimerComesWithTheContact() {
        val dad = "00000000-0000-4000-8000-0000000000d0"
        fx.db.upsertConversation(jid = mum, name = null, isGroup = false)

        sync.ingestDeviceContact(
            DeviceContact(
                Optional.of(ServiceId.ACI.parseOrNull(mum)!!), Optional.of(mumPhone), Optional.empty(),
                Optional.empty(), Optional.of(604_800), Optional.of(2), Optional.empty(),
            ),
        )
        sync.ingestDeviceContact(
            DeviceContact(
                Optional.of(ServiceId.ACI.parseOrNull(dad)!!), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(3_600), Optional.empty(), Optional.empty(),
            ),
        )

        assertEquals(ChatTimer(604_800, 2), fx.db.chatTimer(mum))
        assertEquals(emptyList(), fx.db.getMessages(mum))
        fx.db.upsertConversation(jid = dad, name = null, isGroup = false)
        assertEquals(null, fx.db.chatTimer(dad))
    }
}
