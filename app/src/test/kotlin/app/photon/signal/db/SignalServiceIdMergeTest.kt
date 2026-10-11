package app.photon.signal.db

import androidx.test.core.app.ApplicationProvider
import app.photon.signal.planReadSync
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A contact first seen by their phone-number identity (PNI) gets a second
 * conversation keyed by that PNI. Its incoming rows can never be read-synced
 * (a read sync names the author's ACI), so once the ACI is known everything
 * must move over to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SignalServiceIdMergeTest {

    private lateinit var db: SignalMessageDatabase
    private val me = "00000000-0000-4000-8000-00000000000a"
    private val aci = "00000000-0000-4000-8000-00000000a11c"
    private val pni = "PNI:00000000-0000-4000-8000-0000000000b1"
    private val phone = "+15550001111"
    private val t = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = SignalMessageDatabase(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    private fun receive(conversation: String, author: String, ts: Long, rand: String = "r", alreadyRead: Boolean = false) =
        db.insertIncomingMessage(
            id = "${author}_${ts}_$rand", conversationJid = conversation, senderJid = author,
            timestamp = ts / 1000, contentType = "text", textBody = "m$ts", alreadyRead = alreadyRead,
        )

    /** The PNI conversation as a receiver without the ACI mapping would have stored it. */
    private fun storePniConversation() {
        db.upsertConversation(jid = pni, name = phone, isGroup = false)
        db.updateContactPhone(pni, phone)
        db.updateContactProfileKey(pni, ByteArray(32) { 7 })
        receive(pni, pni, t + 1_000)
        db.markConversationSeen(pni) // seen in Photon, read sync impossible
        receive(pni, pni, t + 2_000)
        receive(pni, pni, t + 3_000)
        db.insertMessage(
            id = "${me}_${t + 2_500}_o", conversationJid = pni, senderJid = me,
            timestamp = (t + 2_500) / 1000, contentType = "text", textBody = "reply",
            replyToId = "${pni}_${t + 1_000}", isFromMe = true, status = "sent",
        )
        db.upsertReaction("${pni}_${t + 2_000}", me, "👍", 1)
        db.upsertReaction("${aci}_${t}", pni, "❤️", 1)
        db.upsertCallRow("call_${pni}_9", pni, pni, (t + 500) / 1000, "Missed voice call", unread = true)
        db.upsertConversation(jid = "group", name = "Group", isGroup = true)
        db.upsertParticipant("group", pni)
    }

    @Test
    fun mergeMovesEverythingToTheAciConversation() {
        storePniConversation()
        // Contacts sync has since learned the ACI; its conversation has the
        // first message and, delivered again under the ACI, the third.
        db.updateContactPhone(aci, phone)
        db.upsertConversation(jid = aci, name = "Alice", isGroup = false)
        receive(aci, aci, t, alreadyRead = true)
        receive(aci, aci, t + 3_000, rand = "again")

        assertTrue(db.mergeServiceId(pni, aci))

        assertNull(db.getConversation(pni))
        val merged = db.getConversation(aci)!!
        assertEquals("Alice", merged.name)
        // t+2000 and the missed call from the PNI side, t+3000 once (the ACI copy).
        assertEquals(3, merged.unreadCount)
        assertEquals("${aci}_${t + 3_000}_again", merged.lastMessageId)

        assertEquals("seen", db.getMessage("${aci}_${t + 1_000}_r")?.status)
        assertEquals("received", db.getMessage("${aci}_${t + 2_000}_r")?.status)
        assertNull(db.getMessage("${pni}_${t + 3_000}_r"))
        assertNull(db.getMessage("${aci}_${t + 3_000}_r"))
        assertEquals("Missed voice call", db.getMessage("call_${aci}_9")?.textBody)
        assertEquals(setOf(aci, me), db.getMessages(aci).map { it.senderJid }.toSet())
        assertEquals(6, db.getMessages(aci).size)
        assertEquals("${aci}_${t + 1_000}", db.getMessage("${me}_${t + 2_500}_o")?.replyToId)

        assertEquals(listOf("👍"), db.getReactions(listOf("${aci}_${t + 2_000}_r")).values.single().map { it.emoji })
        assertEquals(listOf(aci), db.getReactions(listOf("${aci}_${t}_r")).values.single().map { it.senderJid })
        assertEquals(listOf(aci), db.getParticipants("group").map { it.jid })

        assertEquals(phone, db.getContact(aci)?.phone)
        assertContentEquals(ByteArray(32) { 7 }, db.getContact(aci)?.profileKey)
        // The PNI's contact row stays as an alias; nothing else is left to merge.
        assertEquals(phone, db.getContact(pni)?.phone)
        assertFalse(db.mergeServiceId(pni, aci))

        // The seen row can now go out in a read sync.
        val plan = planReadSync(db.getUnsyncedIncomingReads(aci))
        assertEquals(listOf("${aci}_${t + 1_000}_r"), plan.rowIds)
        assertEquals(emptyList(), plan.skippedIds)
    }

    /** A dropped duplicate that was a group's newest message mustn't leave the group pointing at nothing. */
    @Test
    fun aGroupWhoseLastMessageWasADroppedCopyPointsAtTheNewestLeft() {
        db.upsertConversation(jid = "group", name = "Group", isGroup = true)
        db.updateContactPhone(pni, phone)
        db.updateContactPhone(aci, phone)
        receive("group", aci, t + 1_000, rand = "aci")
        receive("group", aci, t + 2_000, rand = "aci")
        receive("group", pni, t + 2_000, rand = "pni") // the same message under the PNI, stored last
        assertEquals("${pni}_${t + 2_000}_pni", db.getConversation("group")?.lastMessageId)

        assertTrue(db.mergeServiceId(pni, aci))

        assertEquals("${aci}_${t + 2_000}_aci", db.getConversation("group")?.lastMessageId)
        assertEquals(2, db.getConversation("group")?.unreadCount)
    }

    @Test
    fun withoutAnAciConversationThePniOneIsRenamed() {
        storePniConversation()
        db.updateContactPhone(aci, phone)

        assertTrue(db.mergeServiceId(pni, aci))

        val merged = db.getConversation(aci)!!
        assertEquals(phone, merged.name)
        assertEquals(3, merged.unreadCount)
        assertEquals(listOf(aci), db.getConversations().filter { !it.isGroup }.map { it.jid })
    }

    /** A sent transcript with no service id is keyed by the phone number until the ACI is known. */
    @Test
    fun aPhoneKeyedConversationMergesIntoTheAci() {
        db.upsertConversation(jid = phone, name = phone, isGroup = false)
        db.updateContactPhone(phone, phone)
        db.insertMessage(
            id = "${me}_${t}_o", conversationJid = phone, senderJid = me, timestamp = t / 1000,
            contentType = "text", textBody = "sent from the primary", isFromMe = true, status = "sent",
        )
        db.updateConversationLastMessage(phone, "${me}_${t}_o", t / 1000)
        db.updateContactPhone(aci, phone)

        assertEquals(listOf(phone to aci), db.getResolvableNonAciIds())
        assertTrue(db.mergeServiceId(phone, aci))

        assertNull(db.getConversation(phone))
        assertEquals("${me}_${t}_o", db.getConversation(aci)?.lastMessageId)
        assertEquals(aci, db.getMessage("${me}_${t}_o")?.conversationJid)
        assertFalse(db.mergeServiceId(phone, aci))
    }

    @Test
    fun nothingStoredUnderTheIdIsANoOp() {
        db.upsertConversation(jid = aci, name = "Alice", isGroup = false)

        assertFalse(db.mergeServiceId(pni, aci))
        assertFalse(db.mergeServiceId(aci, aci))
        assertEquals("Alice", db.getConversation(aci)?.name)
    }

    @Test
    fun onlyNonAciIdsWhosePhoneMapsToAnAciAreResolvable() {
        val unmapped = "PNI:00000000-0000-4000-8000-0000000000b2"
        db.updateContactPhone(pni, phone)
        db.updateContactPhone(unmapped, "+15550002222")
        db.updateContactPhone(phone, phone) // a phone-keyed sent transcript
        assertEquals(emptyList(), db.getResolvableNonAciIds())

        db.updateContactPhone(aci, phone)

        assertEquals(setOf(pni to aci, phone to aci), db.getResolvableNonAciIds().toSet())
    }
}
