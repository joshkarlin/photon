package app.photon.signal.receiver

import app.photon.data.model.Message
import app.photon.signal.db.SignalMessageDatabase.RemoteDeleteChange
import app.photon.signal.db.insertIncomingMessage
import app.photon.signal.db.updateContactPhone
import app.photon.signal.db.updateContactProfileName
import app.photon.signal.db.upsertCallRow
import app.photon.signal.db.upsertConversation
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * A sender deletes a message for everyone while it, or others, are still
 * unread. The notification must never keep showing the deleted text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RemoteDeleteNotificationTest {

    private val local = "00000000-0000-4000-8000-00000000000a"
    private val alice = "00000000-0000-4000-8000-00000000a11c"
    private val bob = "00000000-0000-4000-8000-000000000b0b"
    private val group = "Z3JvdXAtaWQtZm9yLXRlc3RzLTMyLWJ5dGVzLWxvbmc="

    private lateinit var fx: ReceiverFixture
    private lateinit var edits: RemoteEdits

    @Before
    fun setUp() {
        fx = ReceiverFixture(local)
        val notifications = MessageNotifications(fx.db, fx.names, fx.notifier)
        edits = RemoteEdits(fx.db, notifications)
        fx.db.upsertConversation(jid = alice, name = null, isGroup = false)
        fx.db.updateContactProfileName(alice, "Alice")
        fx.db.upsertConversation(jid = group, name = "Climbing", isGroup = true)
    }

    @After
    fun tearDown() = fx.close()

    /** Stored unread, with its notification up. */
    private fun receive(conversation: String, author: String, ts: Long, text: String) {
        fx.db.insertIncomingMessage(
            id = "${author}_${ts}_r", conversationJid = conversation, senderJid = author,
            timestamp = ts / 1000, contentType = "text", textBody = text,
        )
        fx.notifier.showing += conversation
    }

    private fun message(text: String?) = Message(
        id = "x", conversationJid = alice, senderJid = alice, timestamp = 0, contentType = "text", textBody = text,
        mediaUrl = null, mediaMime = null, mediaSize = null, thumbnailPath = null, stickerPackId = null,
        replyToId = null, editVersion = 0, isFromMe = false, status = "received",
    )

    @Test
    fun selection() {
        val newest = message("still unread")
        assertEquals(
            AfterRemoteDelete.Cancel,
            notificationAfterRemoteDelete(RemoteDeleteChange(alice, 0, newerUnread = false)) { newest },
        )
        assertEquals(
            AfterRemoteDelete.Keep,
            notificationAfterRemoteDelete(RemoteDeleteChange(alice, 2, newerUnread = true)) { newest },
        )
        assertEquals(
            AfterRemoteDelete.Show(newest),
            notificationAfterRemoteDelete(RemoteDeleteChange(alice, 1, newerUnread = false)) { newest },
        )
        assertEquals(
            AfterRemoteDelete.Show(null),
            notificationAfterRemoteDelete(RemoteDeleteChange(alice, 1, newerUnread = false)) { null },
        )
    }

    @Test
    fun deletingTheNewestUnreadMessageShowsTheOneBeforeIt() {
        receive(alice, alice, 1_700_000_000_000, "see you at 8")
        receive(alice, alice, 1_700_000_100_000, "oops, wrong chat")

        edits.applyRemoteDelete("${alice}_1700000100000")

        assertEquals(listOf("message:$alice:Alice:see you at 8"), fx.notifier.events)
    }

    @Test
    fun inAGroupTheRepostNamesTheGroupAndTheSender() {
        fx.db.updateContactPhone(alice, "+15550001111")
        fx.addressBook["+15550001111"] = "Ally"
        receive(group, alice, 1_700_000_000_000, "first")
        receive(group, bob, 1_700_000_100_000, "deleted text")

        edits.applyRemoteDelete("${bob}_1700000100000")

        assertEquals(listOf("message:$group:Climbing:Ally: first"), fx.notifier.events)
    }

    /** The user swiped the notification away: a delete mustn't bring it back. */
    @Test
    fun aDismissedNotificationIsNotRepostedAfterADelete() {
        receive(alice, alice, 1_700_000_000_000, "see you at 8")
        receive(alice, alice, 1_700_000_100_000, "oops, wrong chat")
        fx.notifier.showing -= alice

        edits.applyRemoteDelete("${alice}_1700000100000")

        assertEquals(emptyList(), fx.notifier.events)
    }

    @Test
    fun deletingAnOlderUnreadMessageLeavesTheNotificationAlone() {
        receive(alice, alice, 1_700_000_000_000, "deleted text")
        receive(alice, alice, 1_700_000_100_000, "newest")

        edits.applyRemoteDelete("${alice}_1700000000000")

        assertEquals(emptyList(), fx.notifier.events)
    }

    @Test
    fun deletingTheOnlyUnreadMessageClearsTheNotification() {
        receive(alice, alice, 1_700_000_000_000, "deleted text")

        edits.applyRemoteDelete("${alice}_1700000000000")

        assertEquals(listOf("cancel:$alice"), fx.notifier.events)
    }

    /** Only a missed call is left unread: no message text to show, so a neutral one. */
    @Test
    fun withOnlyAMissedCallLeftTheRepostIsNeutral() {
        receive(alice, alice, 1_700_000_000_000, "deleted text")
        fx.db.upsertCallRow("call_${alice}_1", alice, alice, 1_699_999_000, "Missed voice call", unread = true)

        edits.applyRemoteDelete("${alice}_1700000000000")

        assertEquals(listOf("message:$alice:Alice:null"), fx.notifier.events)
    }
}
