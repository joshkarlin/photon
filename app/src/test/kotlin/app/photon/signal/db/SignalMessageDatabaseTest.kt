package app.photon.signal.db

import androidx.test.core.app.ApplicationProvider
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
@Config(manifest = Config.NONE, sdk = [33])
class SignalMessageDatabaseTest {

    private lateinit var db: SignalMessageDatabase

    @Before
    fun setUp() {
        db = SignalMessageDatabase(ApplicationProvider.getApplicationContext())
    }

    /**
     * A contacts/profile re-resolution pass must NEVER wipe a group's title.
     * Groups have no phone/profile row, so the resolver returns null — the old
     * code wrote that null over the title, which then rendered as a base64 JID.
     */
    @Test
    fun reresolveConversationNames_preservesGroupTitle_andUpdatesDms() {
        db.upsertConversation(jid = "groupB64Id", name = "Frothers Utd", isGroup = true)
        db.upsertConversation(jid = "aci-dm", name = "+15550001111", isGroup = false)
        db.updateContactPhone("aci-dm", "+15550001111")

        val changed = db.reresolveConversationNames { phone ->
            if (phone == "+15550001111") "Alice" else null
        }

        assertEquals("Frothers Utd", db.getConversation("groupB64Id")?.name) // group untouched
        assertEquals("Alice", db.getConversation("aci-dm")?.name)            // DM upgraded
        assertEquals(1, changed)
    }

    /**
     * The blank-group-name repair must target only groups that are actually
     * blank AND have a master key to re-fetch with — never named groups, never
     * keyless ones. (Restores titles wiped by the old reresolve bug.)
     */
    @Test
    fun getGroupsWithBlankNameAndMasterKey_selectsOnlyRepairableGroups() {
        val mk = ByteArray(32) { 1 }
        db.upsertConversation(jid = "blankGroup", name = null, isGroup = true)
        db.updateGroupMeta("blankGroup", mk, 5)
        db.upsertConversation(jid = "namedGroup", name = "Frothers Utd", isGroup = true)
        db.updateGroupMeta("namedGroup", mk, 5)
        db.upsertConversation(jid = "keylessGroup", name = null, isGroup = true)

        val repairable = db.getGroupsWithBlankNameAndMasterKey()

        assertEquals(listOf("blankGroup"), repairable.map { it.first })
        assertEquals(32, repairable.single().second.size)
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
        db.upsertParticipant("meta-ready", "meta-member", "Member", "member")
        db.upsertConversation(jid = "meta-keyless", name = null, isGroup = true)

        val refreshable = db.getGroupsNeedingMetadataRefresh()
            .map { it.first }
            .filter { it.startsWith("meta-") }
            .toSet()

        assertEquals(setOf("meta-blank", "meta-no-participants"), refreshable)
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
        assertEquals("deleted", msg.status)
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
        db.upsertParticipant(groupJid, groupMemberA, "Member A", "member")
        db.upsertParticipant(groupJid, groupMemberB, null, "member")

        val targets = db.getSessionPingTargetAcis()
            .filter { it.startsWith("ping-") }
            .toSet()

        assertEquals(setOf(dmAci, groupMemberA, groupMemberB), targets)
    }
}
