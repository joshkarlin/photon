package app.photon.ui.shared.components

import app.photon.data.model.ContentTypes
import app.photon.data.model.Message
import app.photon.data.model.MessageStatus
import app.photon.ui.shared.components.MessageAction.DELETE
import app.photon.ui.shared.components.MessageAction.DELETE_FOR_EVERYONE
import app.photon.ui.shared.components.MessageAction.REACT
import app.photon.ui.shared.components.MessageAction.REPLY
import app.photon.ui.shared.components.MessageAction.RETRY
import org.junit.Test
import kotlin.test.assertEquals

/** Which rows a long-pressed message's menu offers, by message and platform. */
class MessageActionRulesTest {
    /** WhatsApp and Signal. */
    private val full = MessageActionSupport(reply = true, react = true, retry = true, delete = true, deleteForEveryone = true)

    /** SMS: Photon isn't the default SMS app, so it can only resend. */
    private val sms = MessageActionSupport(retry = true)

    private fun message(
        fromMe: Boolean,
        status: MessageStatus = if (fromMe) MessageStatus.SENT else MessageStatus.UNREAD,
        contentType: String = "text",
    ) = Message(
        id = "m", conversationJid = "chat", senderJid = if (fromMe) "me" else "them", timestamp = 1_700_000_000,
        contentType = contentType, textBody = "hello", mediaUrl = null, mediaMime = null, mediaSize = null,
        thumbnailPath = null, stickerPackId = null, replyToId = null, editVersion = 0, isFromMe = fromMe,
        status = status,
    )

    @Test
    fun incomingMessagesCanBeRepliedToReactedToAndDeleted() {
        assertEquals(listOf(REPLY, REACT, DELETE), messageActions(message(fromMe = false), full))
        assertEquals(listOf(REPLY, REACT, DELETE), messageActions(message(fromMe = false, MessageStatus.READ), full))
    }

    @Test
    fun ownSentMessagesCanBeDeletedForEveryoneButNotReactedTo() {
        listOf(MessageStatus.SENT, MessageStatus.DELIVERED, MessageStatus.READ).forEach { status ->
            assertEquals(listOf(REPLY, DELETE, DELETE_FOR_EVERYONE), messageActions(message(fromMe = true, status), full))
        }
    }

    @Test
    fun aPlatformWithoutDeleteForEveryoneOffersOnlyDelete() {
        val noRevoke = full.copy(deleteForEveryone = false)
        assertEquals(listOf(REPLY, DELETE), messageActions(message(fromMe = true), noRevoke))
    }

    @Test
    fun ownFailedMessagesCanBeRetriedOrDeleted() {
        assertEquals(listOf(RETRY, DELETE), messageActions(message(fromMe = true, MessageStatus.FAILED), full))
        // Still on its way: nothing others have yet, so nothing to reply to or revoke.
        assertEquals(listOf(DELETE), messageActions(message(fromMe = true, MessageStatus.SENDING), full))
    }

    @Test
    fun deletedMessagesCanOnlyBeRemoved() {
        assertEquals(listOf(DELETE), messageActions(message(fromMe = false, MessageStatus.DELETED), full))
        assertEquals(listOf(DELETE), messageActions(message(fromMe = true, MessageStatus.DELETED), full))
    }

    @Test
    fun callRowsCanOnlyBeDeleted() {
        assertEquals(listOf(DELETE), messageActions(message(fromMe = false, contentType = "call"), full))
        assertEquals(listOf(DELETE), messageActions(message(fromMe = true, contentType = "call"), full))
    }

    @Test
    fun smsOffersOnlyRetryOnAFailedSend() {
        assertEquals(emptyList(), messageActions(message(fromMe = false), sms))
        assertEquals(emptyList(), messageActions(message(fromMe = true), sms))
        assertEquals(listOf(RETRY), messageActions(message(fromMe = true, MessageStatus.FAILED), sms))
        assertEquals(emptyList(), messageActions(message(fromMe = false, contentType = "call"), sms))
    }

    @Test
    fun reactionsNeverGoOnYourOwnMessages() {
        MessageStatus.entries.forEach { status ->
            assert(REACT !in messageActions(message(fromMe = true, status), full)) { "own $status message offers React" }
        }
    }

    @Test
    fun systemLinesOfferNothing() {
        assertEquals(emptyList(), messageActions(message(fromMe = false, contentType = ContentTypes.SYSTEM), full))
        assertEquals(emptyList(), messageActions(message(fromMe = true, contentType = ContentTypes.SYSTEM), full))
    }

    /** View-once media and undelivered messages: no reply, reaction or anything else but Delete. */
    @Test
    fun phoneOnlyContentCanOnlyBeDeleted() {
        listOf(ContentTypes.VIEW_ONCE, ContentTypes.UNAVAILABLE).forEach { type ->
            assertEquals(listOf(DELETE), messageActions(message(fromMe = false, contentType = type), full), type)
            assertEquals(listOf(DELETE), messageActions(message(fromMe = true, contentType = type), full), type)
            assertEquals(emptyList(), messageActions(message(fromMe = false, contentType = type), sms), type)
        }
    }

    @Test
    fun locationsAreMessagesLikeAnyOther() {
        assertEquals(listOf(REPLY, REACT, DELETE), messageActions(message(fromMe = false, contentType = ContentTypes.LOCATION), full))
        assertEquals(
            listOf(REPLY, DELETE, DELETE_FOR_EVERYONE),
            messageActions(message(fromMe = true, contentType = ContentTypes.LOCATION), full),
        )
    }
}
