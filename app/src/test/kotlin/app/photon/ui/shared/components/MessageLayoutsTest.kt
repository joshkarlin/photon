package app.photon.ui.shared.components

import app.photon.data.model.ContentTypes
import app.photon.data.model.Message
import app.photon.data.model.MessageStatus
import org.junit.Test
import java.util.Calendar
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageLayoutsTest {

    private fun message(
        id: String = "m",
        sender: String = "alice@s.whatsapp.net",
        timestamp: Long = 1_000_000L,
        contentType: String = "text",
        body: String? = "hi",
        fromMe: Boolean = false,
        mediaUrl: String? = null,
    ) = Message(
        id = id, conversationJid = "chat", senderJid = sender, timestamp = timestamp,
        contentType = contentType, textBody = body, mediaUrl = mediaUrl, mediaMime = null,
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

    @Test
    fun systemLinesStandAloneAndShowTheirLine() {
        val line = message(contentType = ContentTypes.SYSTEM, body = "Disappearing messages · 1 week")
        assertEquals("Disappearing messages · 1 week", formatContent(line))
        assertFalse(isPlaceholder(line))
        assertTrue(startsBurst(line, message()))
        assertTrue(startsBurst(message(timestamp = line.timestamp + 1), line))
    }

    /** View-once media and undelivered messages say what they are and where to see them, and recede. */
    @Test
    fun phoneOnlyContentPointsToThePhone() {
        assertEquals("[ view-once photo · open on your phone ]", formatContent(message(contentType = ContentTypes.VIEW_ONCE, body = "photo")))
        assertEquals("[ view-once video · open on your phone ]", formatContent(message(contentType = ContentTypes.VIEW_ONCE, body = "video")))
        assertEquals("[ view-once voice message · open on your phone ]", formatContent(message(contentType = ContentTypes.VIEW_ONCE, body = "voice")))
        assertEquals("[ view-once message · open on your phone ]", formatContent(message(contentType = ContentTypes.VIEW_ONCE, body = null)))
        assertEquals("[ waiting for this message · open on your phone ]", formatContent(message(contentType = ContentTypes.UNAVAILABLE, body = null)))
        listOf(ContentTypes.VIEW_ONCE, ContentTypes.UNAVAILABLE).forEach { type ->
            val msg = message(contentType = type, body = "photo")
            assertTrue(isPlaceholder(msg), type)
            assertTrue(isPhoneOnly(msg), type)
            assertFalse(isMedia(msg), "$type opens nothing")
        }
    }

    @Test
    fun locationsShowTheirNameAndCoordinates() {
        val named = message(contentType = ContentTypes.LOCATION, body = "Buckingham Palace", mediaUrl = "geo:51.501364,-0.14189")
        assertEquals("Location · Buckingham Palace", formatContent(named))
        assertEquals("Location", formatContent(message(contentType = ContentTypes.LOCATION, body = null)))
        assertEquals("Location", formatContent(message(contentType = ContentTypes.LOCATION, body = "  ")))
        assertFalse(isPlaceholder(named))
        assertEquals("51.5014, -0.1419", formatCoordinates(parseGeo(named.mediaUrl)!!))
    }

    @Test
    fun geoUrisParseOnlyWhenTheyHoldAPoint() {
        assertEquals(GeoPoint(51.5007, -0.1246), parseGeo("geo:51.5007,-0.1246"))
        assertEquals(GeoPoint(-33.8568, 151.2153), parseGeo("GEO:-33.8568,151.2153,40;u=35?z=12"))
        assertNull(parseGeo(null))
        assertNull(parseGeo("https://maps.example/51.5,-0.12"))
        assertNull(parseGeo("geo:51.5"))
        assertNull(parseGeo("geo:north,west"))
        assertNull(parseGeo("geo:91,0"))
        assertNull(parseGeo("geo:0,181"))
        // Coordinates read the same in every locale.
        assertEquals("-0.1000, 0.0001", formatCoordinates(GeoPoint(-0.1, 0.0001)))
    }

    @Test
    fun contactsKeepTheirName() {
        assertEquals("[ contact: Sam Okafor ]", formatContent(message(contentType = "contact", body = "Sam Okafor")))
    }

    /** A reply to one of the new rows quotes what it is, not its raw text. */
    @Test
    fun quotesSayWhatTheNewRowsAre() {
        assertEquals("↳ Alice: view-once photo", quoteLabel(message(contentType = ContentTypes.VIEW_ONCE, body = "photo"), "Alice"))
        assertEquals("↳ waiting for this message", quoteLabel(message(contentType = ContentTypes.UNAVAILABLE, body = null), null))
        assertEquals("↳ Location · Big Ben", quoteLabel(message(contentType = ContentTypes.LOCATION, body = "Big Ben"), null))
    }
}
