package app.photon.signal.db

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Deletes-for-everyone and reaction removals arriving from other people/devices. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SignalRemoteEditsTest {

    private lateinit var db: SignalMessageDatabase
    private val alice = "00000000-0000-4000-8000-00000000a11c"

    @Before
    fun setUp() {
        db = SignalMessageDatabase(ApplicationProvider.getApplicationContext())
        db.upsertConversation(jid = alice, name = "Alice", isGroup = false)
    }

    @After
    fun tearDown() = db.close()

    private fun receive(ts: Long) = db.insertIncomingMessage(
        id = "${alice}_${ts}_r$ts",
        conversationJid = alice,
        senderJid = alice,
        timestamp = ts / 1000,
        contentType = "text",
        textBody = "msg $ts",
    )

    private fun unread() = db.getConversation(alice)?.unreadCount

    /** Deleting the only unread message clears the badge and reports it. */
    @Test
    fun remoteDeleteOfUnreadMessageDecrementsAndReportsCleared() {
        receive(1_700_000_000_000)

        val changes = db.markDeletedByPrefix("${alice}_1700000000000")

        assertEquals(listOf(SignalMessageDatabase.RemoteDeleteChange(alice, 0, newerUnread = false)), changes)
        assertEquals(0, unread())
        assertEquals("deleted", db.getMessage("${alice}_1700000000000_r1700000000000")?.status)
    }

    /** The older of two unread messages: the newer one is what the notification shows. */
    @Test
    fun remoteDeleteKeepsCountOfOtherUnreadMessages() {
        receive(1_700_000_000_000)
        receive(1_700_000_100_000)

        val changes = db.markDeletedByPrefix("${alice}_1700000000000")

        assertEquals(listOf(SignalMessageDatabase.RemoteDeleteChange(alice, 1, newerUnread = true)), changes)
        assertEquals(1, unread())
    }

    /** The newest unread message: what is left unread is older, so the notification is stale. */
    @Test
    fun remoteDeleteOfNewestUnreadReportsTheOlderMessageStillUnread() {
        receive(1_700_000_000_000)
        receive(1_700_000_100_000)

        val changes = db.markDeletedByPrefix("${alice}_1700000100000")

        assertEquals(listOf(SignalMessageDatabase.RemoteDeleteChange(alice, 1, newerUnread = false)), changes)
        assertEquals("msg 1700000000000", db.newestUnreadMessage(alice)?.textBody)
    }

    /** A missed call isn't a message: it has its own notification. */
    @Test
    fun newestUnreadMessageSkipsCallRows() {
        receive(1_700_000_000_000)
        db.upsertCallRow("call_${alice}_1", alice, alice, 1_700_000_200, "Missed voice call", unread = true)

        assertEquals("msg 1700000000000", db.newestUnreadMessage(alice)?.textBody)
        db.markDeletedByPrefix("${alice}_1700000000000")
        assertEquals(null, db.newestUnreadMessage(alice))
    }

    /** Already-seen message deleted: nothing to subtract, and a later read sync can't subtract either. */
    @Test
    fun remoteDeleteOfSeenMessageDoesNotTouchCount() {
        receive(1_700_000_000_000)
        db.markConversationSeen(alice)
        receive(1_700_000_100_000)

        assertEquals(emptyList(), db.markDeletedByPrefix("${alice}_1700000000000"))
        assertEquals(1, unread())
        db.markIncomingReadByPrefix("${alice}_1700000000000")
        assertEquals(1, unread())
    }

    @Test
    fun removeReaction_dropsOnlyThatSendersReaction() {
        receive(1_700_000_000_000)
        val prefix = "${alice}_1700000000000"
        db.upsertReaction(prefix, "bob", "👍", 1)
        db.upsertReaction(prefix, "carol", "❤️", 2)

        db.removeReaction(prefix, "bob")

        val reactions = db.getReactions(listOf("${prefix}_r1700000000000")).values.single()
        assertEquals(listOf("carol"), reactions.map { it.senderJid })
    }
}
