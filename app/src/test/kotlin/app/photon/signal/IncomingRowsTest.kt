package app.photon.signal

import app.photon.data.model.ContentTypes
import okio.ByteString.Companion.toByteString
import org.junit.Test
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.DataMessage.Contact
import org.whispersystems.signalservice.internal.push.DataMessage.PollCreate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a DataMessage is stored as: nothing it carries is dropped. */
class IncomingRowsTest {

    private fun pointer(mime: String, fileName: String? = null) =
        AttachmentPointer(cdnKey = "k-$mime", contentType = mime, key = ByteArray(64).toByteString(), fileName = fileName)

    private fun shape(rows: List<PlannedRow>) = rows.map { it.contentType to it.text }

    @Test
    fun everyAttachmentIsARowAndTheCaptionGoesOnTheFirstPhotoOrVideo() {
        val rows = plannedRows(
            DataMessage(
                body = "beach",
                attachments = listOf(pointer("application/pdf", "plan.pdf"), pointer("image/jpeg"), pointer("video/mp4")),
            ),
        )

        assertEquals(listOf("document" to "plan.pdf", "image" to "beach", "video" to null), shape(rows))
        assertEquals(listOf("application/pdf", "image/jpeg", "video/mp4"), rows.map { it.attachment?.contentType })
    }

    /** A document or voice note can't show a caption: the body is a text row after it. */
    @Test
    fun aBodyWithOnlyDocumentsIsItsOwnRow() {
        val rows = plannedRows(DataMessage(body = "see attached", attachments = listOf(pointer("application/pdf", "a.pdf"))))
        assertEquals(listOf("document" to "a.pdf", "text" to "see attached"), shape(rows))
    }

    @Test
    fun aPlainMessageIsOneTextRow() {
        assertEquals(listOf("text" to "hi"), shape(plannedRows(DataMessage(body = "hi"))))
    }

    @Test
    fun nothingToShowIsNoRows() {
        assertEquals(emptyList(), plannedRows(DataMessage(profileKey = ByteArray(32).toByteString())))
        assertEquals(emptyList(), plannedRows(DataMessage(body = "  ")))
    }

    @Test
    fun aStickerAloneIsAStickerRow() {
        assertEquals(listOf("sticker" to null), shape(plannedRows(DataMessage(sticker = DataMessage.Sticker(stickerId = 1)))))
    }

    /** View-once media is one row naming its kind, with no pointer: it can never be downloaded here. */
    @Test
    fun viewOnceMediaKeepsOnlyItsKind() {
        val photo = plannedRows(DataMessage(isViewOnce = true, attachments = listOf(pointer("image/jpeg"))))
        assertEquals(listOf(ContentTypes.VIEW_ONCE to "photo"), shape(photo))
        assertNull(photo.single().attachment)

        assertEquals("video", plannedRows(DataMessage(isViewOnce = true, attachments = listOf(pointer("video/mp4")))).single().text)
        assertEquals("voice", viewOnceKind("audio/aac"))
        assertEquals("photo", viewOnceKind(null))
    }

    @Test
    fun aPollIsReadableText() {
        val rows = plannedRows(DataMessage(pollCreate = PollCreate(question = "Where shall we eat?", options = listOf("Pizza", " Thai ", ""))))
        assertEquals(listOf("text" to "Poll: Where shall we eat?\n• Pizza\n• Thai"), shape(rows))
        assertEquals("Poll", pollText(PollCreate()))
    }

    @Test
    fun aSharedContactIsItsNameAndNumbers() {
        val jane = Contact(
            name = Contact.Name(givenName = "Jane", familyName = "Doe"),
            number = listOf(Contact.Phone(value_ = "+44 7700 900123"), Contact.Phone(value_ = "+1 555 0100")),
        )
        val rows = plannedRows(DataMessage(contact = listOf(jane, Contact(organization = "Acme Ltd"))))

        assertEquals(
            listOf("contact" to "Jane Doe · +44 7700 900123 · +1 555 0100", "contact" to "Acme Ltd"),
            shape(rows),
        )
        assertEquals("Jo", contactText(Contact(name = Contact.Name(nickname = "Jo"))))
        assertEquals("+1 555 0100", contactText(Contact(number = listOf(Contact.Phone(value_ = "+1 555 0100")))))
        assertNull(contactText(Contact()))
    }

    @Test
    fun documentNamesAreFitToShow() {
        assertEquals("report.pdf", documentName("report.pdf"))
        assertEquals("evil.pdf", documentName("../../evil.pdf"))
        // A right-to-left override would show "invoiceexe.pdf".
        assertEquals("invoicefdp.exe", documentName("invoice\u202Efdp.exe"))
        assertEquals("a b.txt", documentName(" a\u0000 b.txt\n"))
        assertNull(documentName(null))
        assertNull(documentName(".."))
        assertNull(documentName("folder/"))
        assertEquals(200, documentName("x".repeat(500))!!.length)
    }

    @Test
    fun theNotificationShowsTheBodyElseTheFirstThing() {
        val photos = plannedRows(DataMessage(body = "beach", attachments = listOf(pointer("image/jpeg"), pointer("image/jpeg"))))
        assertEquals("beach" to "text", notificationContent(photos, "beach"))
        assertEquals(null to "document", notificationContent(plannedRows(DataMessage(attachments = listOf(pointer("application/pdf", "a.pdf")))), null))
        assertEquals("photo" to ContentTypes.VIEW_ONCE, notificationContent(plannedRows(DataMessage(isViewOnce = true)), null))
        val poll = plannedRows(DataMessage(pollCreate = PollCreate(question = "Q")))
        assertEquals("Poll: Q" to "text", notificationContent(poll, null))
    }

    @Test
    fun partIdsKeepTheMessagePrefix() {
        val id = "aci_1700000000123_abcd1234"
        assertEquals(id, MessageKeys.partId(id, 0))
        assertEquals("$id.2", MessageKeys.partId(id, 1))
        assertEquals("aci_1700000000123", MessageKeys.prefixOf(MessageKeys.partId(id, 2)))
        assertTrue(MessageKeys.isExtraPart("$id.3"))
        assertFalse(MessageKeys.isExtraPart(id))
        assertEquals(("aci" to 1_700_000_000_123L), MessageKeys.parse("$id.2"))
    }
}
