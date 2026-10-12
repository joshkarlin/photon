package app.photon.signal.receiver

import app.photon.data.model.MessageStatus
import app.photon.signal.db.getConversation
import app.photon.signal.db.getMessages
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.SyncMessage
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A sent transcript from the primary says the user saw the chat up to the
 * message they sent, so earlier messages are read there and start their
 * disappearing clocks. Only a transcript that shows something counts, and
 * nothing that arrived after it is touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class TranscriptReadTest {

    private val local = "00000000-0000-4000-8000-00000000000a"
    private val alice = "00000000-0000-4000-8000-00000000a11c"
    private val t0 = 1_700_000_000_000L

    private lateinit var fx: ReceiverFixture
    private lateinit var h: Handlers

    @Before
    fun setUp() {
        fx = ReceiverFixture(local)
        h = Handlers(fx)
    }

    @After
    fun tearDown() = fx.close()

    private fun receive(body: String, ts: Long, timer: Int = 30) {
        h.data.processDataMessage(DataMessage(body = body, timestamp = ts, expireTimer = timer, expireTimerVersion = 2), h.from(alice))
        fx.notifier.showing += alice
    }

    private fun transcript(data: DataMessage) = h.sync.processSyncMessage(
        SyncMessage(sent = SyncMessage.Sent(destinationServiceId = alice, timestamp = data.timestamp, message = data)),
        h.from(local),
    )

    private fun expiresAt(body: String): Long {
        val id = fx.db.getMessages(alice).single { it.textBody == body }.id
        return fx.db.readableDatabase.rawQuery("SELECT expires_at FROM messages WHERE id = ?", arrayOf(id))
            .use { it.moveToFirst(); it.getLong(0) }
    }

    private fun status(body: String) = fx.db.getMessages(alice).single { it.textBody == body }.status

    /**
     * The primary sends content-less transcripts on its own (a profile-key
     * update after an unsealed message arrives, a group-call join, a poll
     * vote): they read nothing and start no clock.
     */
    @Test
    fun aContentlessTranscriptReadsNothing() {
        receive("are you there?", t0)

        transcript(DataMessage(timestamp = t0 + 2_000, profileKey = ByteArray(32) { 1 }.toByteString(), flags = 4))
        transcript(DataMessage(timestamp = t0 + 3_000, pollVote = DataMessage.PollVote(targetSentTimestamp = 1)))

        assertEquals(MessageStatus.UNREAD, status("are you there?"))
        assertEquals(1, fx.db.getConversation(alice)?.unreadCount)
        assertEquals(0, expiresAt("are you there?"))
        assertEquals(emptyList(), fx.notifier.events.filter { it.startsWith("cancel") })
    }

    /** A message that arrived after the one the user sent is still unread, and its notification stays. */
    @Test
    fun aTranscriptDoesntReadNewerMessages() {
        receive("before", t0)
        receive("after", t0 + 10_000)

        transcript(DataMessage(body = "replying", timestamp = t0 + 5_000))

        assertEquals(MessageStatus.READ, status("before"))
        assertEquals(MessageStatus.UNREAD, status("after"))
        assertEquals(1, fx.db.getConversation(alice)?.unreadCount)
        assertEquals(emptyList(), fx.notifier.events.filter { it.startsWith("cancel") })
    }

    /** A real transcript starts the clocks of what it read, and only those. */
    @Test
    fun aTranscriptStartsClocksOnlyUpToItself() {
        receive("before", t0)
        receive("after", t0 + 10_000)

        transcript(DataMessage(body = "replying", timestamp = t0 + 5_000))

        assertTrue(expiresAt("before") > 0)
        assertEquals(0, expiresAt("after"))
    }

    /** Everything read: the chat's notifications go. */
    @Test
    fun aTranscriptAfterEverythingClearsTheNotification() {
        receive("hi", t0)

        transcript(DataMessage(body = "hello", timestamp = t0 + 5_000))

        assertEquals(0, fx.db.getConversation(alice)?.unreadCount)
        assertEquals("cancel:$alice", fx.notifier.events.last())
    }
}
