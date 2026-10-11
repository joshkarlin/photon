package app.photon.signal.db

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The ephemeral window: per-chat cap and max age (README "Ephemeral by design"). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SignalRetentionTest {

    private lateinit var db: SignalMessageDatabase
    private val chat = "00000000-0000-4000-8000-0000000c4a77"
    private val day = 86_400L
    private val now = 1_800_000_000L

    @Before
    fun setUp() {
        db = SignalMessageDatabase(ApplicationProvider.getApplicationContext())
        db.upsertConversation(jid = chat, name = "Chat", isGroup = false)
    }

    @After
    fun tearDown() = db.close()

    private fun incoming(ageDays: Long, n: Int): String {
        val tsMs = (now - ageDays * day) * 1000 + n
        val id = "${chat}_${tsMs}_r"
        db.insertIncomingMessage(
            id = id, conversationJid = chat, senderJid = chat,
            timestamp = tsMs / 1000, contentType = "text", textBody = "m$n",
        )
        return id
    }

    private fun outgoing(ageDays: Long, n: Int): String {
        val tsMs = (now - ageDays * day) * 1000 + n
        val id = "me_${tsMs}_r"
        db.insertMessage(
            id = id, conversationJid = chat, senderJid = "me",
            timestamp = tsMs / 1000, contentType = "text", textBody = "o$n",
            isFromMe = true, status = "sent",
        )
        db.updateConversationLastMessage(chat, id, tsMs / 1000)
        return id
    }

    @Test
    fun capKeepsNewestMessagesPerConversation() {
        val ids = (1..5).map { outgoing(ageDays = 0, n = it) }

        val removed = db.pruneMessages(maxPerConversation = 3, maxAgeDays = 0, nowSeconds = now)

        assertEquals(2, removed)
        assertEquals(ids.takeLast(3).reversed(), db.getMessages(chat).map { it.id })
    }

    @Test
    fun ageLimitRemovesOldSeenMessagesAndTheirReactions() {
        val old = outgoing(ageDays = 10, n = 1)
        val fresh = outgoing(ageDays = 1, n = 2)
        db.upsertReaction(old.substringBeforeLast("_"), "x", "👍", 1)

        db.pruneMessages(maxPerConversation = 0, maxAgeDays = 7, nowSeconds = now)

        assertEquals(listOf(fresh), db.getMessages(chat).map { it.id })
        assertEquals(emptyMap(), db.getReactions(listOf(old)))
    }

    /** A message nobody has seen yet survives the age limit (phone off for a week). */
    @Test
    fun ageLimitKeepsUnreadMessages() {
        val unseen = incoming(ageDays = 10, n = 1)

        assertEquals(0, db.pruneMessages(maxPerConversation = 0, maxAgeDays = 7, nowSeconds = now))

        assertEquals(listOf(unseen), db.getMessages(chat).map { it.id })
        assertEquals(1, db.getConversation(chat)?.unreadCount)
    }

    /** Seen in Photon but not yet read-synced: kept until the sync can go out. */
    @Test
    fun ageLimitKeepsSeenRowsAwaitingReadSync() {
        val seen = incoming(ageDays = 10, n = 1)
        db.markConversationSeen(chat)

        assertEquals(0, db.pruneMessages(maxPerConversation = 0, maxAgeDays = 7, nowSeconds = now))

        db.markMessagesRead(listOf(seen))
        assertEquals(1, db.pruneMessages(maxPerConversation = 0, maxAgeDays = 7, nowSeconds = now))
    }

    /** The cap still bounds unread rows; the badge drops with them. */
    @Test
    fun capPrunesUnreadRowsAndAdjustsCount() {
        (1..4).forEach { incoming(ageDays = 0, n = it) }

        db.pruneMessages(maxPerConversation = 2, maxAgeDays = 7, nowSeconds = now)

        assertEquals(2, db.getMessages(chat).size)
        assertEquals(2, db.getConversation(chat)?.unreadCount)
    }

    /** Conversation rows stay (names, group keys); last_message_id never dangles. */
    @Test
    fun conversationSurvivesAndLastMessagePointerIsRepaired() {
        outgoing(ageDays = 10, n = 1)

        db.pruneMessages(maxPerConversation = 50, maxAgeDays = 7, nowSeconds = now)

        val conversation = db.getConversation(chat)
        assertEquals("Chat", conversation?.name)
        assertNull(conversation?.lastMessageId)
    }

    @Test
    fun zeroLimitsPruneNothing() {
        (1..3).forEach { outgoing(ageDays = 100, n = it) }

        assertEquals(0, db.pruneMessages(maxPerConversation = 0, maxAgeDays = 0, nowSeconds = now))
    }
}
