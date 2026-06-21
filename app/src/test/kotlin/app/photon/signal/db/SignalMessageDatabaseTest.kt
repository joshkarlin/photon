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
}
