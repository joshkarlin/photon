package app.photon.signal.receiver

import app.photon.data.model.ContentTypes
import app.photon.data.model.MessageStatus
import app.photon.signal.MessageKeys
import app.photon.signal.SignalGroupV2Manager
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
import org.whispersystems.signalservice.internal.push.EditMessage
import org.whispersystems.signalservice.internal.push.GroupContextV2
import org.whispersystems.signalservice.internal.push.SyncMessage
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What used to be dropped or mangled now shows: every attachment, polls,
 * shared contacts, document names, view-once media (as a placeholder) and
 * edits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class IncomingContentTest {

    private val local = "00000000-0000-4000-8000-00000000000a"
    private val alice = "00000000-0000-4000-8000-00000000a11c"
    private val bob = "00000000-0000-4000-8000-000000000b0b"
    private val masterKey = ByteArray(32) { 3 }
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

    // ── Edits ──

    private fun edit(target: Long, body: String, from: String = alice, group: Boolean = false): Long {
        val ts = ++now
        val groupV2 = if (group) GroupContextV2(masterKey = masterKey.toByteString(), revision = 0) else null
        h.data.processEditMessage(
            EditMessage(targetSentTimestamp = target, dataMessage = DataMessage(body = body, timestamp = ts, groupV2 = groupV2)),
            h.from(from),
        )
        return ts
    }

    @Test
    fun anEditReplacesTheTextInPlace() {
        val ts = receive(DataMessage(timestamp = ++now, body = "see you at 8"))
        receive(DataMessage(timestamp = ++now, body = "bring cake"))

        edit(ts, "see you at 9")

        val rows = thread()
        assertEquals(listOf("see you at 9", "bring cake"), rows.map { it.textBody })
        assertEquals(listOf(1, 0), rows.map { it.editVersion })
        assertEquals(2, fx.db.getConversation(alice)?.unreadCount)
    }

    /** Signal-Android names the latest version: a second edit names the first edit's timestamp. */
    @Test
    fun aSecondEditCanNameTheFirst() {
        val ts = receive(DataMessage(timestamp = ++now, body = "8pm"))
        val firstEdit = edit(ts, "9pm")
        val secondEdit = edit(firstEdit, "10pm")

        assertEquals("10pm", thread().single().textBody)
        assertEquals(2, thread().single().editVersion)

        // Reactions and reads that name an edit find the message.
        receive(DataMessage(timestamp = ++now, reaction = DataMessage.Reaction(emoji = "👍", targetAuthorAci = alice, targetSentTimestamp = secondEdit)), from = bob)
        assertEquals(listOf("👍"), fx.db.getReactions(listOf(thread().single().id)).values.single().map { it.emoji })
        h.sync.processSyncMessage(SyncMessage(read = listOf(SyncMessage.Read(senderAci = alice, timestamp = secondEdit))), h.from(local))
        assertEquals(MessageStatus.READ, thread().single().status)
    }

    /** Only the author can edit: someone else's edit names a key that isn't theirs. */
    @Test
    fun anotherPersonsEditIsIgnored() {
        val ts = receive(DataMessage(timestamp = ++now, body = "mine"))
        edit(ts, "hijacked", from = bob)
        assertEquals("mine", thread().single().textBody)
    }

    @Test
    fun viewOnceAndDeletedMessagesCantBeEdited() {
        val viewOnce = receive(DataMessage(timestamp = ++now, isViewOnce = true, attachments = listOf(pointer("image/jpeg"))))
        edit(viewOnce, "text now")
        assertEquals("photo", thread().single().textBody)
    }

    @Test
    fun aCaptionEditChangesThePhotosCaption() {
        val ts = receive(DataMessage(timestamp = ++now, body = "beach", attachments = listOf(pointer("image/jpeg"), pointer("image/jpeg"))))
        edit(ts, "the beach at dawn")
        assertEquals(listOf("the beach at dawn", null), thread().map { it.textBody })
    }

    @Test
    fun anEditInAGroupMustNameAMessageInThatGroup() {
        val group = SignalGroupV2Manager.jidFromMasterKey(masterKey)
        val inDm = receive(DataMessage(timestamp = ++now, body = "dm text"))
        edit(inDm, "moved to group", group = true)
        assertEquals("dm text", thread().single().textBody)

        val inGroup = receive(DataMessage(timestamp = ++now, body = "group text", groupV2 = GroupContextV2(masterKey = masterKey.toByteString(), revision = 0)))
        edit(inGroup, "group text, edited", group = true)
        assertEquals("group text, edited", thread(group).single().textBody)
    }

    /** The newest unread message was edited: its notification shows the new text, without a second alert. */
    @Test
    fun anUnreadEditCorrectsItsNotification() {
        val ts = receive(DataMessage(timestamp = ++now, body = "tpyo"))
        fx.notifier.showing += alice

        edit(ts, "typo")

        assertEquals("update:$alice:Signal contact:typo", fx.notifier.events.last())
    }

    @Test
    fun theUsersOwnEditFromThePrimaryApplies() {
        val ts = ++now
        val sent = DataMessage(body = "on my way", timestamp = ts)
        h.sync.processSyncMessage(SyncMessage(sent = SyncMessage.Sent(destinationServiceId = alice, timestamp = ts, message = sent)), h.from(local))
        val editTs = ++now
        h.sync.processSyncMessage(
            SyncMessage(
                sent = SyncMessage.Sent(
                    destinationServiceId = alice, timestamp = editTs,
                    editMessage = EditMessage(targetSentTimestamp = ts, dataMessage = DataMessage(body = "on my way, 5 min", timestamp = editTs)),
                ),
            ),
            h.from(local),
        )
        assertEquals(listOf("on my way, 5 min"), thread().map { it.textBody })
    }
}
