package app.photon.signal.receiver

import app.photon.data.model.ContentTypes
import app.photon.data.model.MessageStatus
import app.photon.signal.ChatTimer
import app.photon.signal.SignalGroupV2Manager
import app.photon.signal.db.chatTimer
import app.photon.signal.db.getConversation
import app.photon.signal.db.getMessages
import app.photon.signal.db.upsertConversation
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroup
import org.signal.storageservice.storage.protos.groups.local.DecryptedMember
import org.signal.storageservice.storage.protos.groups.local.DecryptedTimer
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.GroupContextV2
import org.whispersystems.signalservice.internal.push.SyncMessage
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Timers arriving with messages: 1:1 chats follow Signal-Android's version
 * rules and show each change as a line; groups take theirs only from the
 * group's state. Every message keeps its own timer, and its clock starts
 * when it's read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DisappearingReceiveTest {

    private val local = "00000000-0000-4000-8000-00000000000a"
    private val alice = "00000000-0000-4000-8000-00000000a11c"
    private val masterKey = ByteArray(32) { 7 }
    private val week = 604_800
    private var now = 1_700_000_000_000L

    private lateinit var fx: ReceiverFixture
    private lateinit var h: Handlers
    private lateinit var group: String

    @Before
    fun setUp() {
        fx = ReceiverFixture(local)
        h = Handlers(fx) { now }
        group = SignalGroupV2Manager.jidFromMasterKey(masterKey)
    }

    @After
    fun tearDown() = fx.close()

    private fun receive(body: String?, timer: Int?, version: Int?, flags: Int? = null, ts: Long = ++now) =
        h.data.processDataMessage(
            DataMessage(body = body, timestamp = ts, expireTimer = timer, expireTimerVersion = version, flags = flags),
            h.from(alice),
        )

    private fun receiveInGroup(body: String, timer: Int) = h.data.processDataMessage(
        DataMessage(
            body = body, timestamp = ++now, expireTimer = timer, expireTimerVersion = 9,
            groupV2 = GroupContextV2(masterKey = masterKey.toByteString(), revision = 0),
        ),
        h.from(alice),
    )

    private fun transcript(data: DataMessage, destination: String? = alice, expirationStart: Long? = null) =
        h.sync.processSyncMessage(
            SyncMessage(
                sent = SyncMessage.Sent(
                    destinationServiceId = destination, timestamp = data.timestamp,
                    message = data, expirationStartTimestamp = expirationStart,
                ),
            ),
            h.from(local),
        )

    private fun thread(jid: String = alice) = fx.db.getMessages(jid).reversed().map { it.contentType to it.textBody }

    private fun expiry(id: String): Pair<Long, Long> =
        fx.db.readableDatabase.rawQuery("SELECT expires_in, expires_at FROM messages WHERE id = ?", arrayOf(id))
            .use { it.moveToFirst(); it.getLong(0) to it.getLong(1) }

    private fun member(aci: String) = DecryptedMember(
        aciBytes = UUID.fromString(aci).let {
            ByteBuffer.allocate(16).putLong(it.mostSignificantBits).putLong(it.leastSignificantBits).array()
        }.toByteString(),
    )

    // ── 1:1 chats ──

    @Test
    fun aNewTimerIsStoredAndShownBeforeTheMessage() {
        receive("this one disappears", week, 2)

        assertEquals(ChatTimer(week, 2), fx.db.chatTimer(alice))
        assertEquals(
            listOf(ContentTypes.SYSTEM to "Disappearing messages · 1 week", "text" to "this one disappears"),
            thread(),
        )
        // The line is never unread and never notifies.
        assertEquals(1, fx.db.getConversation(alice)?.unreadCount)
        assertEquals(listOf("message:$alice:Signal contact:this one disappears"), fx.notifier.events)
        // Unread: its clock hasn't started.
        val message = fx.db.getMessages(alice).first()
        assertEquals(week.toLong() to 0L, expiry(message.id))
    }

    @Test
    fun aLowerVersionDoesntChangeTheTimer() {
        receive("set at v3", week, 3)
        receive("from an outdated device", 0, 2)

        assertEquals(ChatTimer(week, 3), fx.db.chatTimer(alice))
        assertEquals(1, thread().count { it.first == ContentTypes.SYSTEM })
        // The message itself keeps its own timer: none.
        assertEquals(0L, expiry(fx.db.getMessages(alice).first().id).first)
    }

    @Test
    fun anEqualOrHigherVersionChangesIt() {
        receive("v2", week, 2)
        receive("off at v2", 0, 2)
        assertEquals(ChatTimer(0, 2), fx.db.chatTimer(alice))
        receive("an hour at v4", 3_600, 4)
        assertEquals(ChatTimer(3_600, 4), fx.db.chatTimer(alice))
        assertEquals(
            listOf("Disappearing messages · 1 week", "Disappearing messages off", "Disappearing messages · 1 hour"),
            thread().filter { it.first == ContentTypes.SYSTEM }.map { it.second },
        )
    }

    /** An explicit timer change shows as its line only: nothing unread, no notification. */
    @Test
    fun anExplicitTimerChangeIsOnlyItsLine() {
        receive(null, week, 2, flags = DataMessage.Flags.EXPIRATION_TIMER_UPDATE.value)

        assertEquals(listOf(ContentTypes.SYSTEM to "Disappearing messages · 1 week"), thread())
        assertEquals(0, fx.db.getConversation(alice)?.unreadCount)
        assertEquals(emptyList(), fx.notifier.events)
        assertEquals(week.toLong(), fx.db.getConversation(alice)?.disappearingSeconds)
    }

    /** A redelivered message changes nothing, not even an old timer without a version. */
    @Test
    fun aRedeliveryDoesntBringBackAnOldTimer() {
        val first = ++now
        receive("old client, a week", week, null, ts = first)
        receive("old client, off", 0, null)
        receive("old client, a week", week, null, ts = first)

        assertEquals(0, fx.db.chatTimer(alice).seconds)
        assertEquals(2, fx.db.getMessages(alice).count { it.contentType == "text" })
    }

    /** Reactions carry no timer, and Signal doesn't read one from them. */
    @Test
    fun aReactionLeavesTheTimerAlone() {
        receive("a week", week, 2)
        h.data.processDataMessage(
            DataMessage(
                timestamp = ++now, expireTimerVersion = 5,
                reaction = DataMessage.Reaction(emoji = "👍", targetAuthorAci = alice, targetSentTimestamp = now - 1),
            ),
            h.from(alice),
        )
        assertEquals(ChatTimer(week, 2), fx.db.chatTimer(alice))
    }

    @Test
    fun aReadSyncStartsTheClock() {
        receive("a week", week, 2)
        val message = fx.db.getMessages(alice).first()

        h.sync.processSyncMessage(SyncMessage(read = listOf(SyncMessage.Read(senderAci = alice, timestamp = now))), h.from(local))

        val (expiresIn, expiresAt) = expiry(message.id)
        assertEquals(week.toLong(), expiresIn)
        assertTrue(expiresAt > 0)
        assertEquals(MessageStatus.READ, fx.db.getMessages(alice).first().status)
    }

    // ── Groups ──

    /** Signal ignores the timer on group messages: only the group's state sets it. The message keeps its own. */
    @Test
    fun aGroupMessageDoesntSetTheGroupTimer() {
        receiveInGroup("in the group", week)

        assertEquals(0, fx.db.chatTimer(group).seconds)
        assertEquals(listOf("text" to "in the group"), thread(group))
        assertEquals(week.toLong(), expiry(fx.db.getMessages(group).first().id).first)
    }

    @Test
    fun aGroupTimerComesFromTheGroupState() {
        fx.db.upsertConversation(jid = group, name = null, isGroup = true)

        fx.groupMetadata.applyGroupState(
            group,
            DecryptedGroup(title = "Climbing", disappearingMessagesTimer = DecryptedTimer(duration = week), revision = 3, members = listOf(member(alice))),
        )
        assertEquals(ChatTimer(week, 1), fx.db.chatTimer(group))
        assertEquals("Climbing", fx.db.getConversation(group)?.name)
        // The first read is silent.
        assertEquals(emptyList(), thread(group))

        fx.groupMetadata.applyGroupState(group, DecryptedGroup(title = "Climbing", revision = 4))
        assertEquals(ChatTimer(0, 1), fx.db.chatTimer(group))
        assertEquals(listOf(ContentTypes.SYSTEM to "Disappearing messages off"), thread(group))
    }

    // ── The user's own messages from other devices ──

    @Test
    fun aTranscriptSetsTheTimerAndItsClockStartedOnTheOtherDevice() {
        val ts = ++now
        transcript(DataMessage(body = "sent on my phone", timestamp = ts, expireTimer = week, expireTimerVersion = 3), expirationStart = ts + 500)

        assertEquals(ChatTimer(week, 3), fx.db.chatTimer(alice))
        assertEquals(listOf(ContentTypes.SYSTEM to "Disappearing messages · 1 week", "text" to "sent on my phone"), thread())
        val mine = fx.db.getMessages(alice).first()
        assertTrue(mine.isFromMe)
        assertEquals(week.toLong() to ts + 500 + week * 1000L, expiry(mine.id))
    }

    @Test
    fun aTranscriptAtALowerVersionIsIgnoredButAHigherOneMovesTheVersion() {
        transcript(DataMessage(body = "v3", timestamp = ++now, expireTimer = week, expireTimerVersion = 3))
        transcript(DataMessage(body = "v2", timestamp = ++now, expireTimer = 0, expireTimerVersion = 2))
        assertEquals(ChatTimer(week, 3), fx.db.chatTimer(alice))

        transcript(DataMessage(body = "v5", timestamp = ++now, expireTimer = week, expireTimerVersion = 5))
        assertEquals(ChatTimer(week, 5), fx.db.chatTimer(alice))
        assertEquals(1, thread().count { it.first == ContentTypes.SYSTEM })
    }

    @Test
    fun aGroupTranscriptLeavesTheGroupTimer() {
        transcript(
            DataMessage(
                body = "to the group", timestamp = ++now, expireTimer = week, expireTimerVersion = 4,
                groupV2 = GroupContextV2(masterKey = masterKey.toByteString(), revision = 0),
            ),
            destination = null,
        )
        assertEquals(0, fx.db.chatTimer(group).seconds)
        assertEquals(listOf("text" to "to the group"), thread(group))
    }

    @Test
    fun anExplicitTimerChangeFromTheUsersPhoneIsItsLine() {
        transcript(
            DataMessage(timestamp = ++now, expireTimer = 300, expireTimerVersion = 2, flags = DataMessage.Flags.EXPIRATION_TIMER_UPDATE.value),
        )
        assertEquals(ChatTimer(300, 2), fx.db.chatTimer(alice))
        assertEquals(listOf(ContentTypes.SYSTEM to "Disappearing messages · 5 minutes"), thread())
    }
}
