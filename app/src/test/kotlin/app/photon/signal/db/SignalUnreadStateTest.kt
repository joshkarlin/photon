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

/**
 * Unread bookkeeping for incoming Signal messages: insert + count, local
 * "seen", and read syncs from other devices. Each test pins a
 * user-visible symptom of a real bug (stale badge, eaten count, lingering
 * notification).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SignalUnreadStateTest {

    private lateinit var db: SignalMessageDatabase
    private val alice = "00000000-0000-4000-8000-00000000a11c"

    @Before
    fun setUp() {
        db = SignalMessageDatabase(ApplicationProvider.getApplicationContext())
        db.upsertConversation(jid = alice, name = "Alice", isGroup = false)
    }

    @After
    fun tearDown() = db.close()

    private fun receive(ts: Long, rand: String = "r$ts", alreadyRead: Boolean = false): Boolean =
        db.insertIncomingMessage(
            id = "${alice}_${ts}_$rand",
            conversationJid = alice,
            senderJid = alice,
            timestamp = ts / 1000,
            contentType = "text",
            textBody = "msg $ts",
            dedupePrefix = "${alice}_$ts",
            alreadyRead = alreadyRead,
        )

    private fun unread() = db.getConversation(alice)?.unreadCount

    @Test
    fun insertIncomingMessage_storesCountsAndMovesConversationForward() {
        assertTrue(receive(1_700_000_000_000))

        assertEquals(1, unread())
        assertEquals("received", db.getMessage("${alice}_1700000000000_r1700000000000")?.status)
        assertEquals("${alice}_1700000000000_r1700000000000", db.getConversation(alice)?.lastMessageId)
        assertEquals(1_700_000_000L, db.getConversation(alice)?.lastTimestamp)
    }

    /** A redelivery (new random suffix, same author+timestamp) changes nothing. */
    @Test
    fun insertIncomingMessage_redeliveryIsDroppedWithoutCounting() {
        assertTrue(receive(1_700_000_000_000, rand = "first"))
        assertFalse(receive(1_700_000_000_000, rand = "second"))

        assertEquals(1, unread())
        assertEquals(1, db.getMessages(alice).size)
    }

    /** A late message must not drag the conversation back in the list order. */
    @Test
    fun insertIncomingMessage_lateMessageDoesNotRewindLastMessage() {
        receive(1_700_000_100_000)
        receive(1_700_000_000_000)

        assertEquals(1_700_000_100L, db.getConversation(alice)?.lastTimestamp)
        assertEquals("${alice}_1700000100000_r1700000100000", db.getConversation(alice)?.lastMessageId)
        assertEquals(2, unread())
    }

    /** Read on another device before it arrived: lands read, not counted. */
    @Test
    fun insertIncomingMessage_alreadyReadIsNotCounted() {
        receive(1_700_000_000_000, alreadyRead = true)

        assertEquals(0, unread())
        assertEquals("read", db.getMessage("${alice}_1700000000000_r1700000000000")?.status)
    }

    /**
     * The drift bug: the user reads a chat in Photon (count cleared, read sync
     * not yet sent), a new message arrives, then the primary's read sync for
     * the OLD message lands. It must not eat the new message's count or
     * report the conversation as cleared (which cancelled its notification).
     */
    @Test
    fun readSyncForMessageAlreadySeenInPhotonDoesNotEatNewerUnread() {
        receive(1_700_000_000_000)
        db.markConversationSeen(alice)
        receive(1_700_000_100_000)
        assertEquals(1, unread())

        val result = db.markIncomingReadByPrefix("${alice}_1700000000000")

        assertTrue(result.matched)
        assertEquals(emptyList(), result.clearedConversations)
        assertEquals(1, unread())
        assertEquals("read", db.getMessage("${alice}_1700000000000_r1700000000000")?.status)
        assertEquals("received", db.getMessage("${alice}_1700000100000_r1700000100000")?.status)
    }

    @Test
    fun readSyncForLastUnreadMessageClearsTheConversation() {
        receive(1_700_000_000_000)

        val result = db.markIncomingReadByPrefix("${alice}_1700000000000")

        assertEquals(SignalMessageDatabase.ReadSyncResult(true, listOf(alice)), result)
        assertEquals(0, unread())
    }

    /** No row yet: the caller must learn the read raced ahead of its message. */
    @Test
    fun readSyncForUnknownMessageIsReportedUnmatched() {
        val result = db.markIncomingReadByPrefix("${alice}_1700000000000")

        assertFalse(result.matched)
        assertEquals(emptyList(), result.clearedConversations)
    }

    @Test
    fun markConversationSeen_clearsCountAndQueuesSeenRowsForReadSync() {
        receive(1_700_000_000_000)
        receive(1_700_000_100_000)
        assertEquals(2, unread())

        db.markConversationSeen(alice)

        assertEquals(0, unread())
        assertEquals(
            setOf("${alice}_1700000000000_r1700000000000", "${alice}_1700000100000_r1700000100000"),
            db.getUnsyncedIncomingReads(alice).toSet(),
        )
    }

    @Test
    fun unseenMessagesAreNotOfferedForReadSync() {
        receive(1_700_000_000_000)

        assertEquals(emptyList(), db.getUnsyncedIncomingReads(alice))
    }

    @Test
    fun markMessagesRead_doesNotReviveADeletedMessage() {
        receive(1_700_000_000_000)
        db.markConversationSeen(alice)
        val id = "${alice}_1700000000000_r1700000000000"
        db.markDeletedByPrefix("${alice}_1700000000000")

        db.markMessagesRead(listOf(id))

        assertEquals("deleted", db.getMessage(id)?.status)
    }

    @Test
    fun nothingChangedWhenSeenTwice() {
        receive(1_700_000_000_000)
        db.markConversationSeen(alice)
        db.markConversationSeen(alice)

        assertEquals(0, unread())
        assertNull(db.getMessages(alice).firstOrNull { it.status == "received" })
    }
}
