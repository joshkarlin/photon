package app.photon.signal.db

import androidx.test.core.app.ApplicationProvider
import app.photon.data.model.MessageStatus
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

/** Call log rows: outcome in text_body, read state (and unread count) in status. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SignalCallRowsTest {

    private lateinit var db: SignalMessageDatabase
    private val peer = "00000000-0000-4000-8000-0000000ca11e"
    private val callId = "call_${peer}_123"

    @Before
    fun setUp() {
        db = SignalMessageDatabase(ApplicationProvider.getApplicationContext())
        db.upsertConversation(jid = peer, name = "Caller", isGroup = false)
    }

    @After
    fun tearDown() = db.close()

    private fun unread() = db.getConversation(peer)?.unreadCount

    @Test
    fun ringingRowIsShownButNotCountedUnread() {
        val change = db.upsertCallRow(callId, peer, peer, 1_700_000_000, "Incoming voice call", unread = false)

        assertFalse(change.existed)
        assertEquals(0, unread())
        val row = db.getMessage(callId)!!
        assertEquals("call", row.contentType)
        assertEquals("Incoming voice call", row.textBody)
        assertEquals(callId, db.getConversation(peer)?.lastMessageId)
    }

    @Test
    fun ringingToMissedCountsOnce() {
        db.upsertCallRow(callId, peer, peer, 1_700_000_000, "Incoming voice call", unread = false)

        val change = db.upsertCallRow(callId, peer, peer, 1_700_000_000, "Missed voice call", unread = true)
        db.upsertCallRow(callId, peer, peer, 1_700_000_000, "Missed voice call", unread = true)

        assertTrue(change.existed)
        assertFalse(change.wasUnread)
        assertEquals(1, change.unreadCount)
        assertEquals(1, unread())
        assertEquals("Missed voice call", db.getMessage(callId)?.textBody)
    }

    /** Shown as missed, then the primary reports it was answered: uncount it. */
    @Test
    fun missedToAnsweredUncounts() {
        db.upsertCallRow(callId, peer, peer, 1_700_000_000, "Missed voice call", unread = true)

        val change = db.upsertCallRow(callId, peer, peer, 1_700_000_000, "Voice call", unread = false)

        assertTrue(change.wasUnread)
        assertEquals(0, change.unreadCount)
        assertEquals(MessageStatus.READ, db.getMessage(callId)?.status)
    }

    /** A missed call the user already viewed stays read when its text changes. */
    @Test
    fun viewedMissedCallStaysReadAfterLateUpdate() {
        db.upsertCallRow(callId, peer, peer, 1_700_000_000, "Missed voice call", unread = true)
        db.markConversationSeen(peer)

        val change = db.upsertCallRow(callId, peer, peer, 1_700_000_000, "Voice call", unread = false)

        assertFalse(change.wasUnread)
        assertEquals(0, unread())
        assertEquals(MessageStatus.READ, db.getMessage(callId)?.status)
    }

    @Test
    fun deleteCallRowRemovesItAndItsUnread() {
        db.upsertCallRow(callId, peer, peer, 1_700_000_000, "Missed voice call", unread = true)

        val change = db.deleteCallRow(callId)!!

        assertTrue(change.wasUnread)
        assertEquals(0, change.unreadCount)
        assertNull(db.getMessage(callId))
        assertNull(db.deleteCallRow(callId))
    }

    @Test
    fun findCallRowIdMatchesTheCallIdSuffixOnly() {
        db.upsertCallRow(callId, peer, peer, 1_700_000_000, "Missed voice call", unread = true)
        db.upsertCallRow("call_${peer}_9123", peer, peer, 1_700_000_000, "Missed voice call", unread = true)

        assertEquals(callId, db.findCallRowId(123))
        assertNull(db.findCallRowId(23))
    }

    @Test
    fun markCallRowsReadClearsMissedCallsUpToTimestamp() {
        db.upsertCallRow("call_${peer}_1", peer, peer, 1_700_000_000, "Missed voice call", unread = true)
        db.upsertCallRow("call_${peer}_2", peer, peer, 1_700_000_500, "Missed voice call", unread = true)

        assertEquals(emptyList(), db.markCallRowsRead(peer, upToTimestamp = 1_700_000_100))
        assertEquals(1, unread())
        assertEquals(listOf(peer), db.markCallRowsRead(null, upToTimestamp = 1_700_001_000))
        assertEquals(0, unread())
    }

    /** Read-call-log must not touch ordinary unread messages. */
    @Test
    fun markCallRowsReadLeavesMessagesUnread() {
        db.insertIncomingMessage(
            id = "${peer}_1700000000000_r", conversationJid = peer, senderJid = peer,
            timestamp = 1_700_000_000, contentType = "text", textBody = "hi",
        )
        db.upsertCallRow(callId, peer, peer, 1_700_000_000, "Missed voice call", unread = true)

        assertEquals(emptyList(), db.markCallRowsRead(peer, upToTimestamp = 1_700_001_000))

        assertEquals(1, unread())
        assertEquals(MessageStatus.UNREAD, db.getMessage("${peer}_1700000000000_r")?.status)
    }

    /** Group call announcements dedupe by exact id, not by the message-key prefix. */
    @Test
    fun groupCallRowsDedupeByEraNotPrefix() {
        val group = "grp_abc-="
        db.upsertConversation(jid = group, name = "Group", isGroup = true)
        fun announce(era: String) = db.insertIncomingMessage(
            id = "gcall_${group}_$era", conversationJid = group, senderJid = peer,
            timestamp = 1_700_000_000, contentType = "call", textBody = "Group call started",
            dedupePrefix = null,
        )

        assertTrue(announce("era1"))
        assertFalse(announce("era1"))
        assertTrue(announce("era2"))
        assertEquals(2, db.getConversation(group)?.unreadCount)
    }

    /**
     * Opening the chat clears missed calls too. Call rows go straight to read
     * and are never offered to the read sync: their ids aren't (author, ts)
     * keys, so they could never be synced and would be retried forever.
     */
    @Test
    fun markConversationSeenReadsCallRowsWithoutQueueingThemForReadSync() {
        db.upsertCallRow(callId, peer, peer, 1_700_000_000, "Missed voice call", unread = true)

        db.markConversationSeen(peer)

        assertEquals(0, unread())
        assertEquals(MessageStatus.READ, db.getMessage(callId)?.status)
        assertEquals(emptyList(), db.getUnsyncedIncomingReads(peer))
    }
}
