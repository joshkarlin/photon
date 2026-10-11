package app.photon.signal.receiver

import app.photon.signal.AndroidContactResolver
import app.photon.signal.db.getConversation
import app.photon.signal.db.insertIncomingMessage
import app.photon.signal.db.updateContactPhone
import app.photon.signal.db.upsertConversation
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** When the receiver learns a PNI's ACI, the PNI conversation merges into it. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PniMergeTest {

    private val aci = "00000000-0000-4000-8000-00000000a11c"
    private val pniUuid = "00000000-0000-4000-8000-0000000000b1"
    private val pni = "PNI:$pniUuid"
    private val phone = "+15550001111"

    private lateinit var fx: ReceiverFixture

    @Before
    fun setUp() {
        fx = ReceiverFixture("00000000-0000-4000-8000-00000000000a")
        fx.db.upsertConversation(jid = pni, name = phone, isGroup = false)
        fx.db.updateContactPhone(pni, phone)
        fx.db.insertIncomingMessage(
            id = "${pni}_1700000000000_r", conversationJid = pni, senderJid = pni,
            timestamp = 1_700_000_000, contentType = "text", textBody = "hi",
        )
    }

    @After
    fun tearDown() = fx.close()

    /** At start or after a contacts sync. */
    @Test
    fun resolvableConversationsMergeAndTheirNotificationsGo() {
        fx.db.updateContactPhone(aci, phone)

        fx.contactNames.mergeResolvableServiceIds()

        assertNull(fx.db.getConversation(pni))
        assertEquals(1, fx.db.getConversation(aci)?.unreadCount)
        assertEquals(listOf("cancelRinging:$pni", "cancel:$pni"), fx.notifier.events)
    }

    /** A merge renames call rows, so the ringing ones need watching again; a no-op merge doesn't. */
    @Test
    fun anActualMergeAsksForRingingRowsToBeRewatched() {
        var merges = 0
        val names = ContactNames(
            fx.db, AndroidContactResolver(fx.context), fx.notifier,
            profileFetcher = { null }, onMerged = { merges++ },
        )
        fx.db.updateContactPhone(aci, phone)

        names.mergeResolvableServiceIds()
        names.mergeResolvableServiceIds()

        assertEquals(1, merges)
    }

    @Test
    fun withoutAnAciNothingMoves() {
        fx.contactNames.mergeResolvableServiceIds()

        assertEquals(1, fx.db.getConversation(pni)?.unreadCount)
        assertEquals(emptyList(), fx.notifier.events)
    }

    /** An envelope from the PNI that carries the phone maps it and merges at once. */
    @Test
    fun anEnvelopeThatMapsThePniMergesItsConversation() {
        fx.db.updateContactPhone(aci, phone)

        val key = fx.contactNames.normalizeServiceIdForConversation(
            ServiceId.PNI.parseOrNull(pniUuid)!!, phone, "test",
        )

        assertEquals(aci, key)
        assertNull(fx.db.getConversation(pni))
        assertEquals(1, fx.db.getConversation(aci)?.unreadCount)
    }
}
