package app.photon.signal.db

import androidx.test.core.app.ApplicationProvider
import app.photon.data.model.ContentTypes
import app.photon.data.model.MessageStatus
import app.photon.signal.ChatTimer
import app.photon.signal.PlannedRow
import app.photon.signal.incomingTimerUpdate
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
 * Chat timers, the lines their changes add, each message's clock, and the
 * expiry sweep, on a real database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SignalTimersTest {

    private val alice = "00000000-0000-4000-8000-00000000a11c"
    private val me = "00000000-0000-4000-8000-00000000000a"
    private val group = "Z3JvdXAtaWQ="
    private val week = 604_800
    private val now = 1_700_000_000_000L

    private lateinit var db: SignalMessageDatabase

    @Before
    fun setUp() {
        db = SignalMessageDatabase(ApplicationProvider.getApplicationContext())
        db.upsertConversation(jid = alice, name = null, isGroup = false)
        db.upsertConversation(jid = group, name = "Climbing", isGroup = true)
    }

    @After
    fun tearDown() = db.close()

    private fun lines(jid: String) =
        db.getMessages(jid).filter { it.contentType == ContentTypes.SYSTEM }.map { it.textBody }

    private fun receive(id: String, expiresIn: Int, jid: String = alice, alreadyRead: Boolean = false) =
        db.insertIncomingRows(
            id, listOf(PlannedRow("text", "hi")), jid, alice, now / 1000,
            expiresIn = expiresIn, alreadyRead = alreadyRead, nowMs = now,
        )

    private fun expiresAt(id: String): Long =
        db.readableDatabase.rawQuery("SELECT expires_at FROM messages WHERE id = ?", arrayOf(id))
            .use { it.moveToFirst(); it.getLong(0) }

    // ── Chat timers ──

    /** Photon doesn't guess a chat's timer: until it learns it, it's unknown (and shows as off). */
    @Test
    fun aNewChatsTimerIsUnknown() {
        assertNull(db.chatTimer(alice))
        assertNull(db.chatTimer("no-such-chat"))
        assertEquals(0, db.getConversation(alice)?.disappearingSeconds)
    }

    /** The first timer learned from a message is stored silently: nothing visibly changed. */
    @Test
    fun theFirstTimerLearnedAddsNoLine() {
        db.updateDmTimer(alice, now / 1000) { incomingTimerUpdate(it, week, 2) }

        assertEquals(ChatTimer(week, 2), db.chatTimer(alice))
        assertEquals(week.toLong(), db.getConversation(alice)?.disappearingSeconds)
        assertEquals(emptyList(), lines(alice))
    }

    /** An explicit timer change says the timer was set: its line shows even when the old one wasn't known. */
    @Test
    fun anExplicitChangeShowsItsLineEvenFromUnknown() {
        db.updateDmTimer(alice, now / 1000, explicit = true) { incomingTimerUpdate(it, week, 2) }
        assertEquals(listOf("Disappearing messages · 1 week"), lines(alice))
    }

    /** A change of a known timer is stored and shows as a read, never-unread line in the thread. */
    @Test
    fun aDmTimerChangeAddsItsLine() {
        db.updateDmTimer(alice, now / 1000) { incomingTimerUpdate(it, 0, 2) }
        db.updateDmTimer(alice, now / 1000) { incomingTimerUpdate(it, week, 2) }

        assertEquals(ChatTimer(week, 2), db.chatTimer(alice))
        assertEquals(week.toLong(), db.getConversation(alice)?.disappearingSeconds)
        val line = db.getMessages(alice).single()
        assertEquals(ContentTypes.SYSTEM, line.contentType)
        assertEquals("Disappearing messages · 1 week", line.textBody)
        assertEquals(MessageStatus.READ, line.status)
        assertEquals(0, db.getConversation(alice)?.unreadCount)
        assertEquals(line.id, db.getConversation(alice)?.lastMessageId)
        assertEquals(emptyList(), db.getUnsyncedIncomingReads(alice))

        db.updateDmTimer(alice, now / 1000) { incomingTimerUpdate(it, 0, 3) }
        assertEquals(listOf("Disappearing messages off", "Disappearing messages · 1 week"), lines(alice))
    }

    @Test
    fun anIgnoredUpdateAddsNoLine() {
        db.updateDmTimer(alice, now / 1000) { incomingTimerUpdate(it, week, 3) }
        assertNull(db.updateDmTimer(alice, now / 1000) { incomingTimerUpdate(it, 0, 2) })
        assertEquals(ChatTimer(week, 3), db.chatTimer(alice))
        assertEquals(emptyList(), lines(alice))
    }

    /** Only the version moved: stored, but no line (the timer didn't change). */
    @Test
    fun aVersionOnlyChangeAddsNoLine() {
        db.updateDmTimer(alice, now / 1000) { ChatTimer(0, 2) }
        db.updateDmTimer(alice, now / 1000) { ChatTimer(0, 5) }
        assertEquals(ChatTimer(0, 5), db.chatTimer(alice))
        assertEquals(emptyList(), lines(alice))
    }

    /** A group's first read is silent (a new group, or every group after the upgrade); a change after it shows. */
    @Test
    fun aGroupTimerComesFromItsStateAndOnlyAChangeShows() {
        assertFalse(db.groupTimerCurrent(group))
        assertNull(db.chatTimer(group))

        assertFalse(db.updateGroupTimer(group, week, revision = 3, nowSeconds = now / 1000))
        assertTrue(db.groupTimerCurrent(group))
        assertEquals(ChatTimer(week, 1), db.chatTimer(group))
        assertEquals(emptyList(), lines(group))

        assertTrue(db.updateGroupTimer(group, 0, revision = 4, nowSeconds = now / 1000))
        assertEquals(listOf("Disappearing messages off"), lines(group))
        assertEquals(0, db.getConversation(group)?.disappearingSeconds)
    }

    /**
     * A group change moves the revision: until the group is read at it, the
     * timer may have changed, so it's worth reading again; meanwhile the
     * last one read still stands.
     */
    @Test
    fun aGroupTimerIsStaleOnceTheRevisionMoves() {
        db.updateGroupMeta(group, ByteArray(32) { 1 }, 3)
        db.updateGroupTimer(group, week, revision = 3)
        assertTrue(db.groupTimerCurrent(group))
        assertEquals(ChatTimer(week, 1), db.chatTimer(group))

        db.updateGroupMeta(group, ByteArray(32) { 1 }, 4)
        assertFalse(db.groupTimerCurrent(group))
        assertEquals(ChatTimer(week, 1), db.chatTimer(group))

        db.updateGroupTimer(group, 3_600, revision = 4)
        assertTrue(db.groupTimerCurrent(group))
        assertEquals(ChatTimer(3_600, 1), db.chatTimer(group))
    }

    /** A group the account can't read (not a member, or gone) stops being fetched until a later revision. */
    @Test
    fun aGroupThatCantBeFetchedStopsBeingFetched() {
        val mk = ByteArray(32) { 1 }
        db.updateGroupMeta(group, mk, 6)
        db.upsertParticipant(group, alice)
        assertEquals(listOf(group), db.getGroupsNeedingMetadataRefresh().map { it.first })

        db.markGroupStateUnavailable(group)

        assertEquals(emptyList(), db.getGroupsNeedingMetadataRefresh().map { it.first })
        // Still unknown, so a send goes at version 0.
        assertNull(db.chatTimer(group))

        // Added back: a message at a later revision makes it worth fetching again.
        db.updateGroupMeta(group, mk, 7)
        assertEquals(listOf(group), db.getGroupsNeedingMetadataRefresh().map { it.first })
    }

    /** A fetch that answered for an older revision than one already read changes nothing. */
    @Test
    fun anOlderGroupStateIsIgnored() {
        db.updateGroupTimer(group, week, revision = 5)
        assertFalse(db.updateGroupTimer(group, 0, revision = 4))
        assertEquals(week, db.chatTimer(group)?.seconds)
    }

    @Test
    fun aChatThatIsntAGroupNeedsNoGroupTimer() {
        assertTrue(db.groupTimerCurrent(alice))
        assertTrue(db.groupTimerCurrent("no-such-chat"))
    }

    /**
     * The primary's contact sync gives the timer of a chat Photon may not
     * have seen a message in. It applies to an unknown timer or at a
     * strictly higher version (a snapshot may be older than what a message
     * said), adds no line, and seeds a chat started later.
     */
    @Test
    fun aContactSyncTimerAppliesSilentlyAndSeedsNewChats() {
        db.updateContactFromSync(alice, "+15550001111", null)
        db.updateTimerFromContactSync(alice, week, 1)
        assertEquals(ChatTimer(week, 1), db.chatTimer(alice))
        assertEquals(emptyList(), lines(alice))

        db.updateDmTimer(alice, now / 1000) { ChatTimer(0, 3) }
        // Lower, or the same version: a stale snapshot doesn't apply.
        db.updateTimerFromContactSync(alice, week, 2)
        db.updateTimerFromContactSync(alice, week, 3)
        assertEquals(ChatTimer(0, 3), db.chatTimer(alice))
        // Higher does.
        db.updateTimerFromContactSync(alice, 3_600, 4)
        assertEquals(ChatTimer(3_600, 4), db.chatTimer(alice))

        val bob = "00000000-0000-4000-8000-000000000b0b"
        db.updateContactFromSync(bob, null, "Bob")
        db.updateTimerFromContactSync(bob, 3_600, 2)
        db.updateTimerFromContactSync(bob, week, 2)
        assertNull(db.getConversation(bob))
        db.upsertConversation(jid = bob, name = null, isGroup = false)
        assertEquals(ChatTimer(3_600, 2), db.chatTimer(bob))

        // Someone the contact sync gave no timer starts unknown.
        val carol = "00000000-0000-4000-8000-0000000000c0"
        db.upsertConversation(jid = carol, name = null, isGroup = false)
        assertNull(db.chatTimer(carol))
    }

    // ── Clocks ──

    /** An unread message never disappears: its clock starts when it's read. */
    @Test
    fun anIncomingClockStartsWhenSeenHere() {
        receive("${alice}_1_a", week)
        assertEquals(0, expiresAt("${alice}_1_a"))
        assertNull(db.nextExpiry())

        db.markConversationSeen(alice, nowMs = now)
        assertEquals(now + week * 1000L, expiresAt("${alice}_1_a"))
        assertEquals(now + week * 1000L, db.nextExpiry())
    }

    @Test
    fun anIncomingClockStartsWhenReadElsewhere() {
        receive("${alice}_1_a", 30)
        receive("${alice}_2_b", 30)
        val before = db.expiryScheduled.value

        db.markIncomingReadByPrefix("${alice}_1", nowMs = now)

        assertEquals(now + 30_000, expiresAt("${alice}_1_a"))
        assertEquals(0, expiresAt("${alice}_2_b"))
        assertTrue(db.expiryScheduled.value > before)

        db.markConversationRead(alice, nowMs = now + 5)
        assertEquals(now + 5 + 30_000, expiresAt("${alice}_2_b"))
    }

    @Test
    fun aMessageReadFirstElsewhereStartsAtOnce() {
        receive("${alice}_1_a", 30, alreadyRead = true)
        assertEquals(now + 30_000, expiresAt("${alice}_1_a"))
    }

    @Test
    fun aMessageWithoutATimerHasNoClock() {
        receive("${alice}_1_a", 0)
        db.markConversationSeen(alice, nowMs = now)
        assertEquals(0, expiresAt("${alice}_1_a"))
    }

    @Test
    fun ourOwnClockStartsWhenSent() {
        db.insertMessage("${me}_1_a", alice, me, now / 1000, "text", "hi", isFromMe = true, status = "sending")
        db.setExpiresIn("${me}_1_a", 300)
        db.markOutgoingSent("${me}_1_a", nowMs = now)

        assertEquals(MessageStatus.SENT, db.getMessage("${me}_1_a")?.status)
        assertEquals(now + 300_000, expiresAt("${me}_1_a"))
    }

    @Test
    fun aTranscriptStartsWhenTheOtherDeviceDid() {
        db.insertOwnRows("${me}_1_a", listOf(PlannedRow("text", "hi")), alice, me, now / 1000, expiresIn = 60, expireStartMs = now - 10_000)
        assertEquals(now + 50_000, expiresAt("${me}_1_a"))
    }

    // ── Expiry ──

    @Test
    fun expiredMessagesGoWithTheirReactionsAndAliases() {
        receive("${alice}_1_a", 30)
        receive("${alice}_2_b", 0)
        db.upsertReaction("${alice}_1", me, "👍", now)
        db.applyEdit("${alice}_1", "${alice}_9", alice, "edited")
        db.markConversationSeen(alice, nowMs = now)

        assertEquals(ExpiredMessages.NONE, db.deleteExpiredMessages(now + 29_999))
        val expired = db.deleteExpiredMessages(now + 30_000)

        assertEquals(1, expired.count)
        assertEquals(listOf("${alice}_2_b"), db.getMessages(alice).map { it.id })
        assertEquals(emptyMap(), db.getReactions(listOf("${alice}_1_a")))
        assertEquals("${alice}_9", db.resolveMessagePrefix("${alice}_9"))
        assertEquals(listOf(RemoteDeleteChangeOf(alice, 0)), expired.changes.map { RemoteDeleteChangeOf(it.conversationJid, it.unreadCount) })
        assertNull(db.nextExpiry())
    }

    /** The conversation points at a row that's still there. */
    @Test
    fun theLastMessageMovesBackWhenTheNewestExpires() {
        receive("${alice}_1_a", 0)
        db.insertMessage("${me}_2_b", alice, me, now / 1000 + 1, "image", null, mediaUrl = "/data/x.jpg", isFromMe = true, status = "sending")
        db.updateConversationLastMessage(alice, "${me}_2_b", now / 1000 + 1)
        db.setExpiresIn("${me}_2_b", 10)
        db.markOutgoingSent("${me}_2_b", nowMs = now)

        val expired = db.deleteExpiredMessages(now + 10_000)

        assertEquals(listOf("/data/x.jpg"), expired.mediaPaths)
        assertEquals("${alice}_1_a", db.getConversation(alice)?.lastMessageId)
    }

    /** Unread can't expire, but a count must never outlive its row. */
    @Test
    fun anExpiredUnreadRowStopsCounting() {
        receive("${alice}_1_a", 30)
        receive("${alice}_2_b", 30)
        db.writableDatabase.execSQL("UPDATE messages SET expires_at = ? WHERE id = ?", arrayOf<Any?>(now, "${alice}_2_b"))

        val expired = db.deleteExpiredMessages(now)

        assertEquals(1, db.getConversation(alice)?.unreadCount)
        assertEquals(1, expired.changes.single().unreadCount)
    }

    private data class RemoteDeleteChangeOf(val jid: String, val unread: Int)
}
