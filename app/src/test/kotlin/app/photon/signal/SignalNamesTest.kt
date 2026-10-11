package app.photon.signal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.photon.data.model.Conversation
import app.photon.data.names.DisplayNames
import app.photon.data.names.LocalContacts
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.getConversationsWithContacts
import app.photon.signal.db.getGroupPeople
import app.photon.signal.db.insertMessage
import app.photon.signal.db.updateContactPhone
import app.photon.signal.db.updateContactProfileName
import app.photon.signal.db.upsertConversation
import app.photon.signal.db.upsertParticipant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Signal names resolve when read, from the stored sources, LP3 contacts first. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SignalNamesTest {

    private val self = "00000000-0000-4000-8000-00000000000a"
    private val mum = "00000000-0000-4000-8000-0000000000a1"
    private val bob = "00000000-0000-4000-8000-0000000000b2"
    private val group = "Z3JvdXAtaWQtZm9yLXRlc3RzLTMyLWJ5dGVzLWxvbmc="
    private val mumPhone = "+447700900123"
    private val bobPhone = "+447700900456"

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = SignalMessageDatabase(context)
    private val book = mutableMapOf(mumPhone to "Mum")
    private val names = SignalNames(db, { book[it] }) { self }
    private var savedCountry = ""

    @Before
    fun setUp() {
        savedCountry = DisplayNames.defaultCountry
        DisplayNames.defaultCountry = "GB"
    }

    @After
    fun tearDown() {
        DisplayNames.defaultCountry = savedCountry
        db.close()
    }

    private fun primaryContact(aci: String, name: String) {
        db.writableDatabase.execSQL(
            "INSERT INTO contacts (jid, name) VALUES (?, ?) ON CONFLICT(jid) DO UPDATE SET name = excluded.name",
            arrayOf(aci, name),
        )
    }

    private fun conv(jid: String, name: String? = null, isGroup: Boolean = false) = Conversation(
        jid = jid, name = name, isGroup = isGroup, lastMessageId = null, lastTimestamp = 0,
        unreadCount = 0, isMuted = false, avatarUrl = null, updatedAt = 0,
    )

    @Test
    fun aPersonFollowsThePolicyOrder() {
        db.updateContactPhone(mum, mumPhone)
        primaryContact(mum, "Mother")
        db.updateContactProfileName(mum, "Jane")
        assertEquals("Mum", names.person(mum))

        book.remove(mumPhone)
        assertEquals("Mother", names.person(mum))

        db.writableDatabase.execSQL("UPDATE contacts SET name = NULL WHERE jid = ?", arrayOf(mum))
        assertEquals("Jane", names.person(mum))

        db.updateContactPhone(bob, bobPhone)
        assertEquals("+44 7700 900456", names.person(bob))
        assertEquals("Signal contact", names.person("00000000-0000-4000-8000-0000000000c3"))
    }

    /** The old bug: a profile fetch overwrote the LP3 name, so the title flipped back and forth. */
    @Test
    fun aProfileNameNeverOverridesTheLp3Contact() {
        db.updateContactPhone(mum, mumPhone)
        db.upsertConversation(jid = mum, name = null, isGroup = false)
        db.updateContactProfileName(mum, "Jane")

        assertEquals("Mum", names.title(mum, isGroup = false))
    }

    /** A failed profile fetch used to blank the stored name, so the chat showed the number. */
    @Test
    fun aFailedProfileFetchKeepsTheKnownName() {
        db.updateContactPhone(bob, bobPhone)
        db.updateContactProfileName(bob, "Bob")

        assertFalse(db.storeFetchedProfileName(bob, null))
        assertFalse(db.storeFetchedProfileName(bob, " "))
        assertEquals("Bob", names.person(bob))

        assertTrue(db.storeFetchedProfileName(bob, "Robert"))
        assertEquals("Robert", names.person(bob))
    }

    @Test
    fun titlesCoverGroupsAndNoteToSelf() {
        db.upsertConversation(jid = group, name = "Climbing", isGroup = true)
        assertEquals("Climbing", names.title(group, isGroup = true))
        assertEquals("Signal group", names.title(conv(group, null, isGroup = true), null))
        assertEquals(DisplayNames.NOTE_TO_SELF, names.title(self, isGroup = false))
    }

    /** A conversation keyed by a PNI (its ACI not yet known) is still named through its phone. */
    @Test
    fun aPniKeyedConversationIsNamedThroughItsPhone() {
        val pni = "PNI:00000000-0000-4000-8000-0000000000d4"
        db.updateContactPhone(pni, mumPhone)
        assertEquals("Mum", names.person(pni))
    }

    @Test
    fun theConversationListCarriesEachPersonsSources() {
        db.updateContactPhone(mum, mumPhone)
        db.upsertConversation(jid = mum, name = "stale stored name", isGroup = false)
        db.upsertConversation(jid = group, name = "Climbing", isGroup = true)
        for ((i, jid) in listOf(mum, group).withIndex()) {
            db.insertMessage(id = "${jid}_${i}_x", conversationJid = jid, senderJid = mum, timestamp = i.toLong(), contentType = "text", textBody = "hi")
        }

        val resolved = db.getConversationsWithContacts().associate { (c, contact) -> c.jid to names.title(c, contact) }

        assertEquals(mapOf(mum to "Mum", group to "Climbing"), resolved)
    }

    /** Members, plus anyone who wrote without being stored as one. */
    @Test
    fun groupPeopleIncludeSendersWhoAreNotStoredMembers() {
        db.upsertConversation(jid = group, name = "Climbing", isGroup = true)
        db.upsertParticipant(group, mum)
        db.updateContactPhone(mum, mumPhone)
        db.updateContactProfileName(bob, "Bob")
        db.insertMessage(id = "${bob}_1_x", conversationJid = group, senderJid = bob, timestamp = 1, contentType = "text", textBody = "hi")
        db.insertMessage(id = "${self}_2_x", conversationJid = group, senderJid = self, timestamp = 2, contentType = "text", textBody = "me", isFromMe = true)

        val people = db.getGroupPeople(group).associate { (id, contact) -> id to names.person(id, contact) }

        assertEquals(mapOf(mum to "Mum", bob to "Bob"), people)
    }

    /** Saving "Mum" on the LP3 renames the open list without a reconnect. */
    @Test
    fun theRepositoryReResolvesWhenTheLp3AddressBookChanges() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val contacts = LocalContacts(context) { book[it] }
            book.clear()
            db.updateContactPhone(mum, mumPhone)
            db.upsertConversation(jid = mum, name = null, isGroup = false)
            db.insertMessage(id = "${mum}_1_x", conversationJid = mum, senderJid = mum, timestamp = 1, contentType = "text", textBody = "hi")
            val repo = SignalRepository(db, null, scope, contacts) { self }
            fun titleOfMum() = runBlocking {
                withTimeout(5_000) { repo.conversations.filterNotNull().first { it.isNotEmpty() } }
            }.first { it.jid == mum }.name

            assertEquals("+44 7700 900123", titleOfMum())
            book[mumPhone] = "Mum"
            contacts.invalidate()
            runBlocking {
                withTimeout(5_000) { repo.conversations.filterNotNull().first { list -> list.any { it.name == "Mum" } } }
            }
        } finally {
            scope.cancel()
        }
    }
}
