package app.photon.signal.receiver

import app.photon.data.model.ContentTypes
import app.photon.data.model.MessageStatus
import app.photon.signal.MessageKeys
import app.photon.signal.db.getConversation
import app.photon.signal.db.getMessageRawProto
import app.photon.signal.db.getMessages
import app.photon.signal.db.getReactions
import app.photon.signal.db.getUnsyncedIncomingReads
import app.photon.signal.db.markConversationSeen
import app.photon.signal.planReadSync
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.SyncMessage
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What used to be dropped or mangled now shows: every attachment, polls,
 * shared contacts, document names and view-once media (as a placeholder).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class IncomingContentTest {

    private val local = "00000000-0000-4000-8000-00000000000a"
    private val alice = "00000000-0000-4000-8000-00000000a11c"
    private val bob = "00000000-0000-4000-8000-000000000b0b"
    private var now = 1_700_000_000_000L

    private lateinit var fx: ReceiverFixture
    private lateinit var h: Handlers

    @Before
    fun setUp() {
        fx = ReceiverFixture(local)
        h = Handlers(fx) { now }
    }

    @After
    fun tearDown() = fx.close()

    private fun pointer(mime: String, fileName: String? = null) =
        AttachmentPointer(cdnKey = "k", contentType = mime, key = ByteArray(64).toByteString(), fileName = fileName, size = 10)

    private fun receive(data: DataMessage, from: String = alice): Long {
        val ts = data.timestamp ?: ++now
        h.data.processDataMessage(data.copy(timestamp = ts), h.from(from))
        return ts
    }

    private fun thread(jid: String = alice) = fx.db.getMessages(jid).reversed()

    // ── Attachments ──

    @Test
    fun everyAttachmentIsStoredAndTheMessageNotifiesOnce() {
        val ts = receive(
            DataMessage(
                timestamp = ++now, body = "the beach",
                attachments = listOf(pointer("image/jpeg"), pointer("image/jpeg"), pointer("video/mp4")),
            ),
        )

        val rows = thread()
        assertEquals(listOf("image" to "the beach", "image" to null, "video" to null), rows.map { it.contentType to it.textBody })
        val first = rows.first().id
        assertEquals(listOf(first, "$first.2", "$first.3"), rows.map { it.id })
        assertEquals(setOf("${alice}_$ts"), rows.map { MessageKeys.prefixOf(it.id) }.toSet())
        // Each part can be downloaded.
        rows.forEach { assertNotNull(fx.db.getMessageRawProto(it.id)) }
        assertEquals(3, fx.db.getConversation(alice)?.unreadCount)
        assertEquals(listOf("message:$alice:Signal contact:the beach"), fx.notifier.events)
        assertEquals(rows.last().id, fx.db.getConversation(alice)?.lastMessageId)
    }

    @Test
    fun aReadSyncReadsEveryPartAndSyncsItOnce() {
        val ts = receive(DataMessage(timestamp = ++now, attachments = listOf(pointer("image/jpeg"), pointer("image/png"))))

        fx.db.markConversationSeen(alice)
        val plan = planReadSync(fx.db.getUnsyncedIncomingReads(alice))
        assertEquals(1, plan.entries.size)
        assertEquals(2, plan.rowIds.size)

        h.sync.processSyncMessage(SyncMessage(read = listOf(SyncMessage.Read(senderAci = alice, timestamp = ts))), h.from(local))
        assertEquals(listOf(MessageStatus.READ, MessageStatus.READ), thread().map { it.status })
    }

    @Test
    fun aDeleteLeavesOnePlaceholder() {
        val ts = receive(DataMessage(timestamp = ++now, attachments = listOf(pointer("image/jpeg"), pointer("image/png"))))

        receive(DataMessage(timestamp = ++now, delete = DataMessage.Delete(targetSentTimestamp = ts)))

        val rows = thread()
        assertEquals(1, rows.size)
        assertEquals(MessageStatus.DELETED, rows.single().status)
        assertNull(fx.db.getMessageRawProto(rows.single().id))
        assertEquals(0, fx.db.getConversation(alice)?.unreadCount)
        assertEquals(rows.single().id, fx.db.getConversation(alice)?.lastMessageId)
    }

    @Test
    fun aReactionShowsOnTheFirstPartOnly() {
        val ts = receive(DataMessage(timestamp = ++now, attachments = listOf(pointer("image/jpeg"), pointer("image/png"))))
        receive(
            DataMessage(timestamp = ++now, reaction = DataMessage.Reaction(emoji = "❤️", targetAuthorAci = alice, targetSentTimestamp = ts)),
            from = bob,
        )

        val ids = thread().map { it.id }
        assertEquals(listOf(ids.first()), fx.db.getReactions(ids).keys.toList())
    }

    // ── Documents, contacts, polls ──

    @Test
    fun aDocumentShowsItsNameAndItsCaptionFollows() {
        receive(DataMessage(timestamp = ++now, body = "minutes attached", attachments = listOf(pointer("application/pdf", "Minutes 3 Oct.pdf"))))

        assertEquals(
            listOf("document" to "Minutes 3 Oct.pdf", "text" to "minutes attached"),
            thread().map { it.contentType to it.textBody },
        )
    }

    @Test
    fun aPollAndASharedContactAreReadable() {
        receive(DataMessage(timestamp = ++now, pollCreate = DataMessage.PollCreate(question = "Pub?", options = listOf("Yes", "No"))))
        receive(
            DataMessage(
                timestamp = ++now,
                contact = listOf(DataMessage.Contact(name = DataMessage.Contact.Name(givenName = "Jane"), number = listOf(DataMessage.Contact.Phone(value_ = "+447700900123")))),
            ),
        )

        assertEquals(
            listOf("text" to "Poll: Pub?\n• Yes\n• No", "contact" to "Jane · +447700900123"),
            thread().map { it.contentType to it.textBody },
        )
        assertEquals(
            listOf("message:$alice:Signal contact:Poll: Pub?\n• Yes\n• No", "message:$alice:Signal contact:Jane · +447700900123"),
            fx.notifier.events,
        )
    }

    // ── View-once ──

    @Test
    fun viewOnceMediaIsAPlaceholderThatCountsAndNotifies() {
        receive(DataMessage(timestamp = ++now, isViewOnce = true, attachments = listOf(pointer("image/jpeg"))))

        val row = thread().single()
        assertEquals(ContentTypes.VIEW_ONCE to "photo", row.contentType to row.textBody)
        assertNull(row.mediaMime)
        assertNull(row.mediaUrl)
        assertNull(fx.db.getMessageRawProto(row.id))
        assertEquals(1, fx.db.getConversation(alice)?.unreadCount)
        assertEquals(listOf("message:$alice:Signal contact:view-once photo"), fx.notifier.events)
    }

    /** Opened on the primary: read here too, and its notification goes. */
    @Test
    fun viewOnceOpenedOnThePrimaryIsRead() {
        val ts = receive(DataMessage(timestamp = ++now, isViewOnce = true, attachments = listOf(pointer("video/mp4"))))

        h.sync.processSyncMessage(SyncMessage(viewOnceOpen = SyncMessage.ViewOnceOpen(senderAci = alice, timestamp = ts)), h.from(local))

        assertEquals(MessageStatus.READ, thread().single().status)
        assertEquals(0, fx.db.getConversation(alice)?.unreadCount)
        assertEquals("cancel:$alice", fx.notifier.events.last())
    }

    @Test
    fun viewOnceSentFromThePrimaryIsAPlaceholderToo() {
        val ts = ++now
        h.sync.processSyncMessage(
            SyncMessage(
                sent = SyncMessage.Sent(
                    destinationServiceId = alice, timestamp = ts,
                    message = DataMessage(timestamp = ts, isViewOnce = true, attachments = listOf(pointer("image/jpeg"))),
                ),
            ),
            h.from(local),
        )
        val row = thread().single()
        assertEquals(ContentTypes.VIEW_ONCE to "photo", row.contentType to row.textBody)
        assertNull(fx.db.getMessageRawProto(row.id))
    }
}
