package app.photon.signal.db

import androidx.test.core.app.ApplicationProvider
import app.photon.data.model.MessageStatus
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Behavioural tests for the Signal message store, run against real SQLite via
 * Robolectric. Each test asserts an *expected user-facing behaviour* that a
 * real bug violated, not just "the code does what it does".
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SignalMessageDatabaseTest {

    private lateinit var db: SignalMessageDatabase

    @Before
    fun setUp() {
        db = SignalMessageDatabase(ApplicationProvider.getApplicationContext())
    }

    /**
     * Reconnect-time GroupV2 refresh must also pick up named groups with an
     * empty participants table, because those member ACIs are the session-ping
     * targets for group-only contacts.
     */
    @Test
    fun getGroupsNeedingMetadataRefresh_selectsBlankOrParticipantlessGroups() {
        val mk = ByteArray(32) { 2 }
        db.upsertConversation(jid = "meta-blank", name = null, isGroup = true)
        db.updateGroupMeta("meta-blank", mk, 1)
        db.upsertConversation(jid = "meta-no-participants", name = "Known title", isGroup = true)
        db.updateGroupMeta("meta-no-participants", mk, 1)
        db.upsertConversation(jid = "meta-ready", name = "Ready", isGroup = true)
        db.updateGroupMeta("meta-ready", mk, 1)
        db.upsertParticipant("meta-ready", "meta-member")
        db.upsertConversation(jid = "meta-keyless", name = null, isGroup = true)

        val refreshable = db.getGroupsNeedingMetadataRefresh()
            .map { it.first }
            .filter { it.startsWith("meta-") }
            .toSet()

        assertEquals(setOf("meta-blank", "meta-no-participants"), refreshable)
    }

    /**
     * Phone lookup for PNI normalization must return the canonical ACI contact,
     * not a PNI/raw-id alias that learned the same phone from an envelope.
     */
    @Test
    fun getAciForPhone_ignoresPniAliasRows() {
        val phone = "+15550002222"
        val aci = "00000000-0000-4000-8000-000000000001"
        val pniAlias = "PNI:00000000-0000-4000-8000-000000000002"

        db.updateContactPhone(pniAlias, phone)
        assertEquals(null, db.getAciForPhone(phone))

        db.updateContactPhone(aci, phone)

        assertEquals(aci, db.getAciForPhone(phone))
    }

    /**
     * An incoming reaction is stored under the suffix-less "{author}_{ts}"
     * prefix; it must attach to the full message id "{author}_{ts}_{rand}" so it
     * actually renders. (Previously joined on the full id and never matched.)
     */
    @Test
    fun getReactions_attachesPrefixStoredReactionToFullMessageId() {
        val fullId = "aci_1700_abc123"
        db.upsertConversation(jid = "aci", name = "Bob", isGroup = false)
        db.insertMessage(
            id = fullId, conversationJid = "aci", senderJid = "aci",
            timestamp = 1700, contentType = "text", textBody = "hi",
        )
        db.upsertReaction(messageId = "aci_1700", senderJid = "other", emoji = "👍", timestamp = 1701)

        val reactions = db.getReactions(listOf(fullId))

        assertEquals(listOf("👍"), reactions[fullId]?.map { it.emoji })
    }

    /**
     * A remote delete-for-everyone is keyed by the "{author}_{ts}" prefix and
     * must blank the suffixed message + mark it deleted. (An exact-id match
     * against the prefix never hit the stored "_{rand}" id, so deletes no-op'd.)
     */
    @Test
    fun markDeletedByPrefix_blanksSuffixedMessageAndMarksDeleted() {
        val fullId = "aci_1700_abc123"
        db.upsertConversation(jid = "aci", name = "Bob", isGroup = false)
        db.insertMessage(
            id = fullId, conversationJid = "aci", senderJid = "aci",
            timestamp = 1700, contentType = "text", textBody = "secret",
        )

        db.markDeletedByPrefix("aci_1700")

        val msg = db.getMessages("aci").first { it.id == fullId }
        assertEquals("", msg.textBody)
        assertEquals(MessageStatus.DELETED, msg.status)
    }

    @Test
    fun markIncomingReadByPrefix_marksOnlyMatchingDmMessageAndDecrementsUnread() {
        val aci = "read-dm-aci"
        db.upsertConversation(jid = aci, name = "Alice", isGroup = false)
        db.insertIncomingMessage(
            id = "${aci}_1700_abc", conversationJid = aci, senderJid = aci,
            timestamp = 1700, contentType = "text", textBody = "first",
        )
        db.insertIncomingMessage(
            id = "${aci}_1800_def", conversationJid = aci, senderJid = aci,
            timestamp = 1800, contentType = "text", textBody = "second",
        )

        val result = db.markIncomingReadByPrefix("${aci}_1700")

        assertEquals(true, result.matched)
        // The newer message is still unread, so its notification stays.
        assertEquals(emptyList(), result.clearedConversations)
        assertEquals(MessageStatus.READ, db.getMessage("${aci}_1700_abc")?.status)
        assertEquals(MessageStatus.UNREAD, db.getMessage("${aci}_1800_def")?.status)
        assertEquals(1, db.getConversation(aci)?.unreadCount)
    }

    @Test
    fun markIncomingReadByPrefix_resolvesGroupConversationAndIsIdempotent() {
        val group = "read-group"
        val author = "read-group-author"
        db.upsertConversation(jid = group, name = "Group", isGroup = true)
        db.insertIncomingMessage(
            id = "${author}_1700_abc", conversationJid = group, senderJid = author,
            timestamp = 1700, contentType = "text", textBody = "first",
        )
        db.insertIncomingMessage(
            id = "${author}_1800_def", conversationJid = group, senderJid = author,
            timestamp = 1800, contentType = "text", textBody = "second",
        )

        val first = db.markIncomingReadByPrefix("${author}_1700")
        val duplicate = db.markIncomingReadByPrefix("${author}_1700")

        assertEquals(SignalMessageDatabase.ReadSyncResult(true, emptyList()), first)
        assertEquals(SignalMessageDatabase.ReadSyncResult(true, emptyList()), duplicate)
        assertEquals(MessageStatus.READ, db.getMessage("${author}_1700_abc")?.status)
        assertEquals(MessageStatus.UNREAD, db.getMessage("${author}_1800_def")?.status)
        assertEquals(1, db.getConversation(group)?.unreadCount)
    }

    @Test
    fun markIncomingReadByPrefix_doesNotChangeOutgoingMessage() {
        val aci = "read-outgoing-aci"
        db.upsertConversation(jid = aci, name = "Alice", isGroup = false)
        db.insertMessage(
            id = "${aci}_1700_abc", conversationJid = aci, senderJid = "me",
            timestamp = 1700, contentType = "text", textBody = "sent",
            isFromMe = true, status = "sent",
        )

        val result = db.markIncomingReadByPrefix("${aci}_1700")

        // An outgoing row with the same key isn't the incoming message the
        // read refers to, so the read is still unmatched.
        assertEquals(SignalMessageDatabase.ReadSyncResult(false, emptyList()), result)
        assertEquals(MessageStatus.SENT, db.getMessage("${aci}_1700_abc")?.status)
    }

    @Test
    fun markConversationRead_clearsIncomingUnreadButPreservesOutgoingStatus() {
        val aci = "transcript-read-aci"
        db.upsertConversation(jid = aci, name = "Alice", isGroup = false)
        db.insertIncomingMessage(
            id = "${aci}_1700_in", conversationJid = aci, senderJid = aci,
            timestamp = 1700, contentType = "text", textBody = "incoming",
        )
        db.insertMessage(
            id = "${aci}_1800_out", conversationJid = aci, senderJid = "me",
            timestamp = 1800, contentType = "text", textBody = "outgoing",
            isFromMe = true, status = "delivered",
        )

        val changed = db.markConversationRead(aci)
        val duplicate = db.markConversationRead(aci)

        assertEquals(1, changed)
        assertEquals(0, duplicate)
        assertEquals(MessageStatus.READ, db.getMessage("${aci}_1700_in")?.status)
        assertEquals(MessageStatus.DELIVERED, db.getMessage("${aci}_1800_out")?.status)
        assertEquals(0, db.getConversation(aci)?.unreadCount)
    }

    /**
     * A sent transcript for an older message (delivered late from the
     * primary) must not move the chat back in the list. A startup repair used
     * to paper over this by recomputing every conversation's timestamp.
     */
    @Test
    fun updateConversationLastMessage_neverMovesBackwards() {
        val aci = "late-transcript-aci"
        db.upsertConversation(jid = aci, name = "Alice", isGroup = false)
        db.insertIncomingMessage(
            id = "${aci}_1800000_new", conversationJid = aci, senderJid = aci,
            timestamp = 1800, contentType = "text", textBody = "newer",
        )

        db.updateConversationLastMessage(aci, "me_1700000_old", 1700)
        assertEquals("${aci}_1800000_new", db.getConversation(aci)?.lastMessageId)
        assertEquals(1800L, db.getConversation(aci)?.lastTimestamp)

        db.updateConversationLastMessage(aci, "me_1900000_next", 1900)
        assertEquals("me_1900000_next", db.getConversation(aci)?.lastMessageId)
        assertEquals(1900L, db.getConversation(aci)?.lastTimestamp)
    }

    /**
     * Session pings must target group-only members too. Otherwise Photon can
     * send into a GroupV2 conversation but never receive other members'
     * messages because their clients have not established a session with this
     * linked device.
     */
    @Test
    fun getSessionPingTargetAcis_includesDmConversationsAndGroupParticipants() {
        val dmAci = "ping-dm-aci"
        val groupJid = "ping-group"
        val groupMemberA = "ping-group-member-a"
        val groupMemberB = "ping-group-member-b"

        db.upsertConversation(jid = dmAci, name = "Alice", isGroup = false)
        db.insertMessage(
            id = "${dmAci}_1700_msg", conversationJid = dmAci,
            senderJid = dmAci, timestamp = 1700,
            contentType = "text", textBody = "hi",
        )
        db.upsertConversation(jid = "ping-empty-dm", name = "No Messages", isGroup = false)
        db.upsertConversation(jid = groupJid, name = "Group", isGroup = true)
        db.upsertParticipant(groupJid, groupMemberA)
        db.upsertParticipant(groupJid, groupMemberB)

        val targets = db.getSessionPingTargetAcis()
            .filter { it.startsWith("ping-") }
            .toSet()

        assertEquals(setOf(dmAci, groupMemberA, groupMemberB), targets)
    }
}
