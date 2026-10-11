package app.photon.signal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.photon.data.model.MessageStatus
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.getConversation
import app.photon.signal.db.getConversationsWithContacts
import app.photon.signal.db.getMessage
import app.photon.signal.db.getMessages
import app.photon.signal.db.getReactions
import app.photon.signal.db.insertIncomingMessage
import app.photon.signal.db.insertMessage
import app.photon.signal.db.updateContactFromSync
import app.photon.signal.db.updateGroupMeta
import app.photon.signal.db.upsertCallRow
import app.photon.signal.db.upsertConversation
import app.photon.signal.store.PhotonProtocolStore
import app.photon.signal.store.SignalProtocolDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentRemoteId
import java.io.File
import java.io.IOException
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records sends instead of going to Signal; can be told to fail. */
internal class FakeTransport : SignalTransport {
    data class Sent(val recipient: Recipient, val content: OutgoingContent, val timestamp: Long)

    val sent = mutableListOf<Sent>()
    val uploads = mutableListOf<Pair<String, Boolean>>()
    var failSends = false
    var failUploads = false

    override fun send(recipient: Recipient, content: OutgoingContent, timestamp: Long) {
        if (failSends) throw IOException("offline")
        sent += Sent(recipient, content, timestamp)
    }

    override fun upload(file: File, mimeType: String, voiceNote: Boolean): SignalServiceAttachmentPointer {
        if (failUploads) throw IOException("upload failed")
        uploads += mimeType to voiceNote
        return SignalServiceAttachmentPointer(
            3, SignalServiceAttachmentRemoteId.from("cdn-key"), mimeType, ByteArray(64),
            Optional.of(file.length().toInt()), Optional.empty(), 0, 0, Optional.empty(), Optional.empty(), 0,
            Optional.of(file.name), voiceNote, false, false, Optional.empty(), Optional.empty(), 0L, null,
        )
    }
}

/**
 * The send pipeline: an outgoing row is stored before the network call and
 * ends "sent" or "failed"; reactions, retries and deletes-for-everyone go to
 * the same DM or group recipient.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SignalMessageSenderTest {

    private val me = "00000000-0000-4000-8000-00000000000a"
    private val alice = "00000000-0000-4000-8000-00000000a11c"
    private val bob = "00000000-0000-4000-8000-000000000b0b"
    private val group = "Z3JvdXAtaWQtZm9yLXRlc3RzLTMyLWJ5dGVzLWxvbmc="
    private val masterKey = ByteArray(32) { 9 }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: SignalMessageDatabase
    private lateinit var protocolDb: SignalProtocolDatabase
    private val transport = FakeTransport()
    private lateinit var sender: SignalMessageSender

    @Before
    fun setUp() {
        db = SignalMessageDatabase(context)
        protocolDb = SignalProtocolDatabase(context)
        val credentials = SignalCredentials(protocolDb).apply { aciString = me }
        sender = SignalMessageSender(credentials, PhotonProtocolStore(protocolDb), db, transport)
        db.upsertConversation(jid = alice, name = "Alice", isGroup = false)
        db.upsertConversation(jid = group, name = "Group", isGroup = true)
        db.updateGroupMeta(group, masterKey, 4)
    }

    @After
    fun tearDown() {
        sender.shutdown()
        db.close()
        protocolDb.close()
    }

    private fun onlyRow(jid: String) = db.getMessages(jid).single()

    private fun file(name: String, bytes: Int = 10) =
        File(context.filesDir, name).apply { writeBytes(ByteArray(bytes) { 1 }) }

    private fun incoming(id: String, conversation: String = alice) = db.insertIncomingMessage(
        id = id, conversationJid = conversation, senderJid = alice,
        timestamp = 1_700_000_000, contentType = "text", textBody = "hi",
    )

    // ── Text ──

    @Test
    fun textToADmIsStoredThenMarkedSent() {
        sender.sendTextMessage(alice, "hello")

        val row = onlyRow(alice)
        assertEquals(MessageStatus.SENT, row.status)
        assertTrue(row.isFromMe)
        assertEquals("hello", row.textBody)
        val sent = transport.sent.single()
        assertEquals(Recipient.Dm(alice, org.signal.core.models.ServiceId.ACI.parseOrNull(alice)!!), sent.recipient)
        assertEquals(OutgoingContent.Message("hello", null), sent.content)
        // The row id carries the exact wire timestamp.
        assertEquals(me to sent.timestamp, MessageKeys.parse(row.id))
        assertEquals(row.id, db.getConversation(alice)?.lastMessageId)
    }

    @Test
    fun textToAGroupGoesToTheGroupWithItsMasterKey() {
        sender.sendTextMessage(group, "hi all")

        val recipient = assertIs<Recipient.Group>(transport.sent.single().recipient)
        assertEquals(group, recipient.conversationJid)
        assertEquals(4, recipient.meta.revision)
        assertEquals(MessageStatus.SENT, onlyRow(group).status)
    }

    @Test
    fun textToAnUnknownConversationIsStoredFailedWithoutSending() {
        sender.sendTextMessage("not-a-chat", "hello")

        assertEquals(MessageStatus.FAILED, onlyRow("not-a-chat").status)
        assertEquals(emptyList(), transport.sent)
    }

    /**
     * A chat started from New message: a contact sync knows their number, but
     * they have no conversation row. The first DM makes one, so the chat is
     * listed and points at its message.
     */
    @Test
    fun aFirstDmToSomeoneNewStartsTheirConversation() {
        db.updateContactFromSync(bob, "+447700900456", "Bob")

        sender.sendTextMessage(bob, "hello")

        val row = onlyRow(bob)
        assertEquals(MessageStatus.SENT, row.status)
        val conversation = db.getConversation(bob)
        assertEquals(false, conversation?.isGroup)
        assertEquals(row.id, conversation?.lastMessageId)
        assertEquals(listOf(bob), db.getConversationsWithContacts().map { it.first.jid })
    }

    /** Offline, it's still listed, with the failed message to retry. */
    @Test
    fun aFirstDmThatFailsStillStartsTheConversation() {
        transport.failSends = true

        sender.sendMediaMessage(bob, file("first.jpg").path, "image/jpeg", caption = null)

        assertEquals(MessageStatus.FAILED, onlyRow(bob).status)
        assertEquals(onlyRow(bob).id, db.getConversation(bob)?.lastMessageId)
    }

    @Test
    fun aSendToSomethingThatIsntAnAciStartsNoConversation() {
        sender.sendTextMessage("not-a-chat", "hello")
        assertNull(db.getConversation("not-a-chat"))
    }

    @Test
    fun aFailedSendLeavesTheRowFailedForRetry() {
        transport.failSends = true

        sender.sendTextMessage(alice, "hello")

        assertEquals(MessageStatus.FAILED, onlyRow(alice).status)
    }

    @Test
    fun aReplyQuotesTheMessageKeyButNotACallRow() {
        incoming("${alice}_1700000000123_r")
        db.upsertCallRow("call_${alice}_5", alice, alice, 1_700_000_100, "Missed voice call", unread = true)

        sender.sendTextMessage(alice, "re", replyToId = "${alice}_1700000000123_r")
        sender.sendTextMessage(alice, "re call", replyToId = "call_${alice}_5")

        assertEquals(
            listOf("${alice}_1700000000123", null),
            transport.sent.map { (it.content as OutgoingContent.Message).replyPrefix },
        )
    }

    // ── Media ──

    @Test
    fun mediaIsUploadedThenSentWithItsCaption() {
        val photo = file("send_media_photo.jpg")

        sender.sendMediaMessage(alice, photo.path, "image/jpeg", "look")

        assertEquals(listOf("image/jpeg" to false), transport.uploads)
        val content = transport.sent.single().content as OutgoingContent.Message
        assertEquals("look", content.text)
        assertEquals(1, content.attachments.size)
        val row = onlyRow(alice)
        assertEquals("image", row.contentType)
        assertEquals(MessageStatus.SENT, row.status)
        assertEquals(photo.path, row.mediaUrl)
    }

    @Test
    fun audioIsSentAsAVoiceNote() {
        sender.sendMediaMessage(alice, file("voice_1.ogg").path, "audio/ogg", null)

        assertEquals(listOf("audio/ogg" to true), transport.uploads)
        assertEquals("audio", onlyRow(alice).contentType)
    }

    @Test
    fun aMissingFileIsStoredFailedWithoutUploading() {
        sender.sendMediaMessage(alice, File(context.filesDir, "gone.jpg").path, "image/jpeg", null)

        assertEquals(MessageStatus.FAILED, onlyRow(alice).status)
        assertEquals(emptyList(), transport.uploads)
        assertEquals(emptyList(), transport.sent)
    }

    @Test
    fun aFailedUploadLeavesTheRowFailed() {
        transport.failUploads = true

        sender.sendMediaMessage(alice, file("send_media_2.jpg").path, "image/jpeg", null)

        assertEquals(MessageStatus.FAILED, onlyRow(alice).status)
        assertEquals(emptyList(), transport.sent)
    }

    // ── Reactions ──

    @Test
    fun aReactionIsSentThenStoredUnderTheMessageKey() {
        incoming("${alice}_1700000000123_r")

        assertTrue(sender.sendReaction(alice, "${alice}_1700000000123_r", "👍"))

        val reaction = assertIs<OutgoingContent.Reaction>(transport.sent.single().content).reaction
        assertEquals("👍", reaction.emoji)
        assertEquals(1700000000123L, reaction.targetSentTimestamp)
        val stored = db.getReactions(listOf("${alice}_1700000000123_r")).values.single().single()
        assertEquals(me to "👍", stored.senderJid to stored.emoji)
    }

    @Test
    fun reactionsAreRefusedForCallsUnsentRowsAndOtherChats() {
        db.upsertCallRow("call_${alice}_5", alice, alice, 1_700_000_100, "Missed voice call", unread = true)
        db.insertMessage(
            id = "${me}_1700000000200_f", conversationJid = alice, senderJid = me, timestamp = 1_700_000_000,
            contentType = "text", textBody = "x", isFromMe = true, status = "failed",
        )
        incoming("${alice}_1700000000123_r")

        assertFalse(sender.sendReaction(alice, "call_${alice}_5", "👍"))
        assertFalse(sender.sendReaction(alice, "${me}_1700000000200_f", "👍"))
        assertFalse(sender.sendReaction(group, "${alice}_1700000000123_r", "👍"))
        assertEquals(emptyList(), transport.sent)
    }

    @Test
    fun aFailedReactionIsNotStored() {
        incoming("${alice}_1700000000123_r")
        transport.failSends = true

        assertFalse(sender.sendReaction(alice, "${alice}_1700000000123_r", "👍"))
        assertEquals(emptyMap(), db.getReactions(listOf("${alice}_1700000000123_r")))
    }

    // ── Retry ──

    @Test
    fun retryResendsWithTheOriginalTimestampAndReply() {
        db.insertMessage(
            id = "${me}_1700000000456_f", conversationJid = alice, senderJid = me, timestamp = 1_700_000_000,
            contentType = "text", textBody = "again", replyToId = "${alice}_1700000000123",
            isFromMe = true, status = "failed",
        )

        assertTrue(sender.retryMessage("${me}_1700000000456_f"))

        val sent = transport.sent.single()
        assertEquals(1700000000456L, sent.timestamp)
        assertEquals(OutgoingContent.Message("again", "${alice}_1700000000123"), sent.content)
        assertEquals(MessageStatus.SENT, db.getMessage("${me}_1700000000456_f")?.status)
    }

    @Test
    fun retryOfMediaUploadsTheStagedFileAgain() {
        val photo = file("send_media_3.jpg")
        db.insertMessage(
            id = "${me}_1700000000456_f", conversationJid = group, senderJid = me, timestamp = 1_700_000_000,
            contentType = "image", textBody = null, mediaUrl = photo.path, mediaMime = "image/jpeg",
            isFromMe = true, status = "failed",
        )

        assertTrue(sender.retryMessage("${me}_1700000000456_f"))

        assertEquals(listOf("image/jpeg" to false), transport.uploads)
        assertIs<Recipient.Group>(transport.sent.single().recipient)
    }

    @Test
    fun aFailedRetryStaysFailed() {
        db.insertMessage(
            id = "${me}_1700000000456_f", conversationJid = alice, senderJid = me, timestamp = 1_700_000_000,
            contentType = "text", textBody = "again", isFromMe = true, status = "failed",
        )
        transport.failSends = true

        assertFalse(sender.retryMessage("${me}_1700000000456_f"))
        assertEquals(MessageStatus.FAILED, db.getMessage("${me}_1700000000456_f")?.status)
    }

    @Test
    fun retryIsRefusedForIncomingRowsGoneFilesAndUnknownChats() {
        incoming("${alice}_1700000000123_r")
        db.insertMessage(
            id = "${me}_1700000000300_m", conversationJid = alice, senderJid = me, timestamp = 1_700_000_000,
            contentType = "image", textBody = null, mediaUrl = "/gone.jpg", isFromMe = true, status = "failed",
        )
        db.insertMessage(
            id = "${me}_1700000000400_u", conversationJid = "not-a-chat", senderJid = me, timestamp = 1_700_000_000,
            contentType = "text", textBody = "x", isFromMe = true, status = "sending",
        )

        assertFalse(sender.retryMessage("${alice}_1700000000123_r"))
        assertFalse(sender.retryMessage("${me}_1700000000300_m"))
        assertFalse(sender.retryMessage("${me}_1700000000400_u"))
        assertEquals(MessageStatus.FAILED, db.getMessage("${me}_1700000000300_m")?.status)
        assertEquals(MessageStatus.FAILED, db.getMessage("${me}_1700000000400_u")?.status)
        assertEquals(emptyList(), transport.sent)
    }

    // ── Delete ──

    @Test
    fun deleteForEveryoneTargetsTheSentTimestampThenDeletesLocally() {
        db.insertMessage(
            id = "${me}_1700000000456_s", conversationJid = alice, senderJid = me, timestamp = 1_700_000_000,
            contentType = "text", textBody = "oops", isFromMe = true, status = "sent",
        )

        assertTrue(sender.deleteMessage(alice, "${me}_1700000000456_s", forEveryone = true))

        assertEquals(OutgoingContent.RemoteDelete(1700000000456L), transport.sent.single().content)
        assertNull(db.getMessage("${me}_1700000000456_s"))
    }

    @Test
    fun aFailedDeleteForEveryoneKeepsTheMessage() {
        db.insertMessage(
            id = "${me}_1700000000456_s", conversationJid = group, senderJid = me, timestamp = 1_700_000_000,
            contentType = "text", textBody = "oops", isFromMe = true, status = "sent",
        )
        transport.failSends = true

        assertFalse(sender.deleteMessage(group, "${me}_1700000000456_s", forEveryone = true))
        assertEquals("oops", db.getMessage("${me}_1700000000456_s")?.textBody)
    }

    @Test
    fun callRowsAndLocalDeletesNeverGoOverTheNetwork() {
        db.upsertCallRow("call_${alice}_5", alice, alice, 1_700_000_100, "Missed voice call", unread = true)
        incoming("${alice}_1700000000123_r")

        assertTrue(sender.deleteMessage(alice, "call_${alice}_5", forEveryone = true))
        assertTrue(sender.deleteMessage(alice, "${alice}_1700000000123_r", forEveryone = false))

        assertEquals(emptyList(), transport.sent)
        assertEquals(emptyList(), db.getMessages(alice))
    }

    @Test
    fun deleteForEveryoneInAnUnknownChatIsRefused() {
        db.insertMessage(
            id = "${me}_1700000000456_s", conversationJid = "not-a-chat", senderJid = me, timestamp = 1_700_000_000,
            contentType = "text", textBody = "oops", isFromMe = true, status = "sent",
        )

        assertFalse(sender.deleteMessage("not-a-chat", "${me}_1700000000456_s", forEveryone = true))
        assertEquals("oops", db.getMessage("${me}_1700000000456_s")?.textBody)
    }
}
