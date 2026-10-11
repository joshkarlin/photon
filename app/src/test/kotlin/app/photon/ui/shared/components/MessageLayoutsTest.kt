package app.photon.ui.shared.components

import app.photon.data.model.Message
import app.photon.data.model.MessageStatus
import org.junit.Test
import java.util.Calendar
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageLayoutsTest {

    private fun message(
        id: String = "m",
        sender: String = "alice@s.whatsapp.net",
        timestamp: Long = 1_000_000L,
        contentType: String = "text",
        body: String? = "hi",
        fromMe: Boolean = false,
    ) = Message(
        id = id, conversationJid = "chat", senderJid = sender, timestamp = timestamp,
        contentType = contentType, textBody = body, mediaUrl = null, mediaMime = null,
        mediaSize = null, thumbnailPath = null, stickerPackId = null, replyToId = null,
        editVersion = 0, isFromMe = fromMe, status = if (fromMe) MessageStatus.SENT else MessageStatus.UNREAD,
    )

    @Test
    fun firstMessageAndSenderChangesStartBursts() {
        val first = message()
        assertTrue(startsBurst(first, previous = null))
        assertFalse(startsBurst(message(timestamp = first.timestamp + 60), first))
        assertTrue(startsBurst(message(sender = "bob@s.whatsapp.net", timestamp = first.timestamp + 60), first))
        assertTrue(startsBurst(message(fromMe = true, timestamp = first.timestamp + 60), first))
    }

    @Test
    fun ownMessagesStayInOneBurstWhateverTheSenderJid() {
        val mine = message(fromMe = true, sender = "me:1@s.whatsapp.net")
        assertFalse(startsBurst(message(fromMe = true, sender = "me:2@s.whatsapp.net", timestamp = mine.timestamp + 5), mine))
    }

    @Test
    fun aLongGapStartsANewBurst() {
        val first = message()
        assertFalse(startsBurst(message(timestamp = first.timestamp + BURST_GAP_SECONDS), first))
        assertTrue(startsBurst(message(timestamp = first.timestamp + BURST_GAP_SECONDS + 1), first))
    }

    @Test
    fun callsStandAlone() {
        val call = message(contentType = "call", body = "Missed voice call")
        assertTrue(startsBurst(call, message()))
        assertTrue(startsBurst(message(timestamp = call.timestamp + 1), call))
    }

    @Test
    fun formatsCallsAndPlaceholders() {
        assertEquals("Missed voice call", formatContent(message(contentType = "call", body = "Missed voice call")))
        assertEquals("Call", formatContent(message(contentType = "call", body = null)))
        assertEquals("[ deleted ]", formatContent(message(body = "")))
        assertEquals("[ unsupported message ]", formatContent(message(contentType = "unsupported", body = "[ unsupported message ]")))
        assertEquals("[ poll ]", formatContent(message(contentType = "poll", body = null)))
        assertEquals("[ image ] look", formatContent(message(contentType = "image", body = "look")))
    }

    @Test
    fun placeholdersRecedeButContentDoesNot() {
        assertTrue(isPlaceholder(message(body = null)))
        assertTrue(isPlaceholder(message(body = "[ unsupported message ]")))
        assertTrue(isPlaceholder(message(contentType = "unsupported", body = "[ unsupported message ]")))
        assertFalse(isPlaceholder(message(body = "hello")))
        assertFalse(isPlaceholder(message(contentType = "image", body = null)))
        assertFalse(isPlaceholder(message(contentType = "call", body = "Missed voice call")))
        assertFalse(isPlaceholder(message(contentType = "location", body = null)))
    }

    @Test
    fun timestampsShowTheDateOnlyBeforeToday() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 11, 15, 30) }
        val earlierToday = (now.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 9) }
        val sameDayLastMonth = (now.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
        val nowMillis = now.timeInMillis

        assertTrue(Regex("""\d\d:\d\d""").matches(formatMessageTime(earlierToday.timeInMillis / 1000, nowMillis)))
        assertTrue(formatMessageTime(sameDayLastMonth.timeInMillis / 1000, nowMillis).length > 5)
    }

    @Test
    fun quoteLabelsNameTheSenderUnlessTheTitleAlreadyDoes() {
        assertEquals("↳ Alice: hi", quoteLabel(message(), "Alice"))
        assertEquals("↳ You: hi", quoteLabel(message(fromMe = true), "Me"))
        // A DM passes no name: the title already says who it is.
        assertEquals("↳ hi", quoteLabel(message(), null))
        assertEquals("↳ You: hi", quoteLabel(message(fromMe = true), null))
    }

    /** A file sent from Files shows as a document, with its name when the platform keeps one. */
    @Test
    fun documentsSayTheyAreDocuments() {
        assertEquals("[ document: report.pdf ]", formatContent(message(contentType = "document", body = "report.pdf")))
        assertEquals("[ document ]", formatContent(message(contentType = "document", body = null)))
    }
}
