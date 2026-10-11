package app.photon.signal.db

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mutes from the storage service, as stored and as conversations show them. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SignalStorageMutesTest {

    private lateinit var db: SignalMessageDatabase
    private val alice = "00000000-0000-4000-8000-00000000a11c"
    private val bob = "00000000-0000-4000-8000-000000000b0b"
    private val group = "Z3JvdXAtaWQtZm9yLXRlc3RzLTMyLWJ5dGVzLWxvbmc="
    private val pni = "PNI:00000000-0000-4000-8000-0000000000b1"
    private val phone = "+15550001111"
    private val now = System.currentTimeMillis() / 1000

    @Before
    fun setUp() {
        db = SignalMessageDatabase(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    private fun mutedUntilOf(jid: String): Long =
        db.readableDatabase.rawQuery("SELECT muted_until FROM conversations WHERE jid = ?", arrayOf(jid))
            .use { if (it.moveToFirst()) it.getLong(0) else error("no row for $jid") }

    @Test
    fun manifestSetsEachConversationsMute() {
        for (jid in listOf(alice, bob, group)) db.upsertConversation(jid = jid, name = null, isGroup = jid == group)

        db.applyStorageManifest(
            version = 7,
            removed = emptyList(),
            added = listOf(
                StorageMute("s-alice", alice, now + 3600),
                StorageMute("s-group", group, -1),
                StorageMute("s-bob", bob, 0),
            ),
        )

        assertEquals(true, db.getConversation(alice)?.isMuted)
        assertEquals(true, db.getConversation(group)?.isMuted)
        assertEquals(false, db.getConversation(bob)?.isMuted)
        assertEquals(7, db.storageManifestVersion())
        assertEquals(setOf("s-alice", "s-group", "s-bob"), db.storageRecordIds())
    }

    @Test
    fun aTimedMuteLapsesOnItsOwn() {
        db.upsertConversation(jid = alice, name = null, isGroup = false)
        db.applyStorageManifest(1, emptyList(), listOf(StorageMute("s-alice", alice, now + 60)))

        assertTrue(db.isConversationMuted(alice, nowSeconds = now))
        assertFalse(db.isConversationMuted(alice, nowSeconds = now + 61))
        assertFalse(db.isConversationMuted("no-such-chat", nowSeconds = now))
    }

    /** A record replaced by a new version (unmuted on the primary) unmutes the chat. */
    @Test
    fun aRemovedRecordTakesItsMuteWithIt() {
        db.upsertConversation(jid = alice, name = null, isGroup = false)
        db.applyStorageManifest(1, emptyList(), listOf(StorageMute("s-alice-1", alice, -1)))

        db.applyStorageManifest(2, listOf("s-alice-1"), listOf(StorageMute("s-alice-2", alice, 0)))

        assertEquals(false, db.getConversation(alice)?.isMuted)
        assertEquals(0, mutedUntilOf(alice))
        assertEquals(setOf("s-alice-2"), db.storageRecordIds())

        db.applyStorageManifest(3, listOf("s-alice-2"), emptyList())
        assertEquals(0, mutedUntilOf(alice))
        assertEquals(emptySet(), db.storageRecordIds())
    }

    /** Two records for one chat (a duplicate on the primary): the longest mute wins. */
    @Test
    fun duplicateRecordsMuteForTheLongest() {
        db.upsertConversation(jid = alice, name = null, isGroup = false)
        db.upsertConversation(jid = bob, name = null, isGroup = false)

        db.applyStorageManifest(
            1, emptyList(),
            listOf(
                StorageMute("a1", alice, now + 60), StorageMute("a2", alice, -1), StorageMute("a3", alice, 0),
                StorageMute("b1", bob, now + 60), StorageMute("b2", bob, now + 600), StorageMute("b3", bob, 0),
            ),
        )

        assertEquals(-1, mutedUntilOf(alice))
        assertEquals(now + 600, mutedUntilOf(bob))
    }

    /** Muted on the primary before Photon had the chat: its first message mustn't notify. */
    @Test
    fun aConversationCreatedLaterTakesItsStoredMute() {
        db.applyStorageManifest(1, emptyList(), listOf(StorageMute("s-alice", alice, -1)))

        db.upsertConversation(jid = alice, name = null, isGroup = false)
        db.upsertConversation(jid = bob, name = null, isGroup = false)

        assertEquals(true, db.getConversation(alice)?.isMuted)
        assertEquals(false, db.getConversation(bob)?.isMuted)
        // Later upserts leave it alone.
        db.upsertConversation(jid = alice, name = null, isGroup = false, lastMessageId = "m", lastTimestamp = now)
        assertEquals(-1, mutedUntilOf(alice))
    }

    @Test
    fun recordsThatMapToNoChatAreRememberedButMuteNothing() {
        db.upsertConversation(jid = alice, name = null, isGroup = false)

        db.applyStorageManifest(1, emptyList(), listOf(StorageMute("s-unknown", null, -1)))

        assertEquals(false, db.getConversation(alice)?.isMuted)
        assertEquals(setOf("s-unknown"), db.storageRecordIds())
    }

    @Test
    fun noVersionBeforeTheFirstRead() {
        assertNull(db.storageManifestVersion())
        assertEquals(emptySet(), db.storageRecordIds())
    }

    /** A PNI-keyed chat merging into its ACI keeps the mute its record gave it. */
    @Test
    fun aMergedPniConversationsMuteMovesToTheAci() {
        db.upsertConversation(jid = pni, name = null, isGroup = false)
        db.insertIncomingMessage(
            id = "${pni}_1700000000000_r", conversationJid = pni, senderJid = pni,
            timestamp = 1_700_000_000, contentType = "text", textBody = "hi",
        )
        db.upsertConversation(jid = alice, name = null, isGroup = false)
        db.applyStorageManifest(1, emptyList(), listOf(StorageMute("s-pni", pni, -1)))
        assertEquals(false, db.getConversation(alice)?.isMuted)

        assertTrue(db.mergeServiceId(pni, alice))

        assertEquals(true, db.getConversation(alice)?.isMuted)
        assertNull(db.getConversation(pni))
    }

    /**
     * A record keyed by a PNI with no chat yet: once a contacts sync maps
     * its phone to the ACI, the startup merge moves the record over, so the
     * ACI chat is muted.
     */
    @Test
    fun aPniOnlyRecordFollowsTheMergeEvenWithoutAChat() {
        db.applyStorageManifest(1, emptyList(), listOf(StorageMute("s-pni", pni, -1)))
        db.updateContactPhone(pni, phone)
        db.updateContactPhone(alice, phone)

        assertEquals(listOf(pni to alice), db.getResolvableNonAciIds())
        assertTrue(db.mergeServiceId(pni, alice))
        db.upsertConversation(jid = alice, name = null, isGroup = false)

        assertEquals(true, db.getConversation(alice)?.isMuted)
        // Moved, so the next startup merge has nothing left to do.
        assertFalse(db.mergeServiceId(pni, alice))
    }
}
