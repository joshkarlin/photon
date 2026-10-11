package app.photon.signal.receiver

import android.util.Base64
import app.photon.signal.EarlyReadCache
import app.photon.signal.db.getConversation
import app.photon.signal.db.getParticipants
import app.photon.signal.db.updateContactPhone
import app.photon.signal.db.updateContactProfileName
import app.photon.signal.db.upsertConversation
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.internal.push.DataMessage
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An incoming message stores no name: the DM title stays the LP3 contact
 * whatever the message or a later profile fetch brings, and notifications
 * name people the same way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class IncomingNamesTest {

    private val local = "00000000-0000-4000-8000-00000000000a"
    private val mum = "00000000-0000-4000-8000-0000000000a1"
    private val mumPhone = "+447700900123"
    private val groupId = ByteArray(32) { it.toByte() }
    private val group = Base64.encodeToString(groupId, Base64.NO_WRAP or Base64.URL_SAFE)

    private lateinit var fx: ReceiverFixture
    private lateinit var handler: DataMessageHandler

    @Before
    fun setUp() {
        fx = ReceiverFixture(local)
        val notifications = MessageNotifications(fx.db, fx.names, fx.notifier)
        val calls = CallHandler(fx.credentials, fx.db, fx.contactNames, fx.names, fx.notifier, schedule = { _, _ -> })
        handler = DataMessageHandler(
            fx.db, fx.contactNames, fx.groupMetadata, calls, RemoteEdits(fx.db, notifications),
            Attachments(fx.context, fx.db) { null }, EarlyReadCache(), notifications, fx.scope,
        )
        fx.db.updateContactPhone(mum, mumPhone)
        fx.db.updateContactProfileName(mum, "Jane")
        fx.addressBook[mumPhone] = "Mum"
    }

    @After
    fun tearDown() = fx.close()

    private fun receive(body: String, ts: Long, groupId: ByteArray? = null) = handler.processDataMessage(
        DataMessage(body = body, timestamp = ts),
        EnvelopeMetadata(ServiceId.ACI.parseOrNull(mum)!!, null, 1, true, groupId, ServiceId.ACI.parseOrNull(local)!!, 0),
    )

    @Test
    fun aDmIsTitledWithTheLp3ContactAndStoresNoName() {
        receive("hi", 1_700_000_000_000)

        assertEquals(listOf("message:$mum:Mum:hi"), fx.notifier.events)
        assertNull(fx.db.getConversation(mum)?.name)
        assertEquals("Mum", fx.names.title(mum, isGroup = false))

        // A profile fetch learns a new name: the title doesn't move.
        fx.db.updateContactProfileName(mum, "Janet")
        receive("still me", 1_700_000_100_000)
        assertEquals("Mum", fx.names.title(mum, isGroup = false))
    }

    @Test
    fun aGroupMessageIsTitledWithTheGroupAndNamesTheSender() {
        fx.db.upsertConversation(jid = group, name = "Climbing", isGroup = true)

        receive("see you at 8", 1_700_000_000_000, groupId)

        assertEquals(listOf("message:$group:Climbing:Mum: see you at 8"), fx.notifier.events)
        assertEquals(listOf(mum to null), fx.db.getParticipants(group).map { it.jid to it.displayName })
    }
}
