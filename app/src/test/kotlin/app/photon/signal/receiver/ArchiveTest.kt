package app.photon.signal.receiver

import app.photon.signal.db.StorageMute
import app.photon.signal.db.applyStorageManifest
import app.photon.signal.db.getConversation
import app.photon.signal.db.getConversationsWithContacts
import app.photon.signal.db.keepMutedChatsArchived
import app.photon.signal.db.unarchivesOnNewMessage
import app.photon.signal.db.upsertConversation
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.DataMessage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Archived chats come from the primary's storage service. A new message
 * takes a chat out of the archive, as on Signal, unless it's muted and the
 * user keeps muted chats archived. Archived chats still notify.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ArchiveTest {

    private val local = "00000000-0000-4000-8000-00000000000a"
    private val alice = "00000000-0000-4000-8000-00000000a11c"
    private var now = 1_700_000_000_000L
    private var version = 0L

    private lateinit var fx: ReceiverFixture
    private lateinit var h: Handlers

    @Before
    fun setUp() {
        fx = ReceiverFixture(local)
        h = Handlers(fx) { now }
        fx.db.upsertConversation(jid = alice, name = null, isGroup = false)
    }

    @After
    fun tearDown() = fx.close()

    private fun store(vararg records: StorageMute) = fx.db.applyStorageManifest(++version, emptyList(), records.toList())

    private fun archived(jid: String = alice) = fx.db.getConversation(jid)?.isArchived

    private fun receive(body: String) =
        h.data.processDataMessage(DataMessage(body = body, timestamp = ++now), h.from(alice))

    @Test
    fun theRule() {
        assertTrue(unarchivesOnNewMessage(muted = false, keepMutedChatsArchived = false))
        assertTrue(unarchivesOnNewMessage(muted = false, keepMutedChatsArchived = true))
        assertTrue(unarchivesOnNewMessage(muted = true, keepMutedChatsArchived = false))
        assertFalse(unarchivesOnNewMessage(muted = true, keepMutedChatsArchived = true))
    }

    @Test
    fun aRecordArchivesItsChatAndAnotherUnarchivesIt() {
        store(StorageMute("s1", alice, 0, archived = true))
        assertEquals(true, archived())

        store(StorageMute("s2", alice, 0, archived = false))
        assertEquals(false, archived())
    }

    /** A chat whose row comes later (its first message) takes the archive its record gave. */
    @Test
    fun aNewChatTakesItsRecordsArchive() {
        val bob = "00000000-0000-4000-8000-000000000b0b"
        store(StorageMute("s1", bob, -1, archived = true), StorageMute("acct", local, 0, keepMutedChatsArchived = true))
        fx.db.upsertConversation(jid = bob, name = null, isGroup = false)
        assertEquals(true, archived(bob))
    }

    /** Archived chats still notify unless muted, and the message takes the chat out of the archive. */
    @Test
    fun aNewMessageUnarchivesAndNotifies() {
        store(StorageMute("s1", alice, 0, archived = true))

        receive("hi")

        assertEquals(false, archived())
        assertEquals(1, fx.notifier.events.size)
    }

    @Test
    fun aMutedChatUnarchivesUnlessMutedChatsStayArchived() {
        store(StorageMute("s1", alice, -1, archived = true))
        receive("hi")
        assertEquals(false, archived())

        store(StorageMute("s2", alice, -1, archived = true), StorageMute("acct", local, 0, keepMutedChatsArchived = true))
        assertTrue(fx.db.keepMutedChatsArchived())
        receive("still muted")
        assertEquals(true, archived())
        // Muted: no notification either.
        assertEquals(0, fx.notifier.events.size)
    }

    /** A timed mute that has lapsed doesn't keep the chat archived. */
    @Test
    fun aLapsedMuteUnarchives() {
        store(StorageMute("s1", alice, now / 1000 - 60, archived = true), StorageMute("acct", local, 0, keepMutedChatsArchived = true))
        receive("hi")
        assertEquals(false, archived())
    }

    /**
     * An unrelated storage change doesn't put a chat a message took out of
     * the archive back in: only a record that's new to Photon (the primary
     * changed that chat) sets it.
     */
    @Test
    fun anUnrelatedStorageChangeLeavesALocalUnarchive() {
        store(StorageMute("s1", alice, 0, archived = true))
        receive("hi")

        fx.db.applyStorageManifest(++version, emptyList(), listOf(StorageMute("other", "someone-else", 0)))

        assertEquals(false, archived())
    }

    /** Note to Self's archive is the account record's. */
    @Test
    fun noteToSelfIsArchivedByTheAccountRecord() {
        fx.db.upsertConversation(jid = local, name = null, isGroup = false)
        store(StorageMute("acct", local, 0, archived = true))
        assertEquals(true, archived(local))
    }

    @Test
    fun theListCarriesTheArchive() {
        store(StorageMute("s1", alice, 0, archived = true))
        fx.db.applyStorageManifest(++version, emptyList(), emptyList())
        receive("hi")
        store(StorageMute("s2", alice, 0, archived = true))

        assertEquals(listOf(true), fx.db.getConversationsWithContacts().map { it.first.isArchived })
    }
}
