package app.photon.ui.shared.components

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The names and types Save writes, and the extensions staged files get. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class FileTypesTest {
    private val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /** Office, OpenDocument, e-book and archive files get their own extensions, not ".bin". */
    @Test
    fun documentTypesHaveTheirExtensions() {
        val expected = mapOf(
            "application/pdf" to ".pdf",
            "application/msword" to ".doc",
            docx to ".docx",
            "application/vnd.ms-excel" to ".xls",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to ".xlsx",
            "application/vnd.ms-powerpoint" to ".ppt",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" to ".pptx",
            "application/vnd.oasis.opendocument.text" to ".odt",
            "application/vnd.oasis.opendocument.spreadsheet" to ".ods",
            "application/vnd.oasis.opendocument.presentation" to ".odp",
            "application/epub+zip" to ".epub",
            "application/zip" to ".zip",
            "text/plain" to ".txt",
            "text/csv" to ".csv",
            "image/jpeg" to ".jpg",
            "audio/ogg; codecs=opus" to ".ogg",
            "Application/PDF" to ".pdf",
        )
        expected.forEach { (mime, ext) -> assertEquals(ext, mimeToExt(mime), mime) }
        assertNull(mimeToExt("application/octet-stream"))
        assertNull(mimeToExt(null))
        assertNull(mimeToExt(""))
    }

    @Test
    fun stagedFilesFallBackByKind() {
        assertEquals(".docx", stagedExtension(docx))
        assertEquals(".jpg", stagedExtension("image/x-unknown"))
        assertEquals(".mp4", stagedExtension("video/x-unknown"))
        assertEquals(".bin", stagedExtension("application/octet-stream"))
    }

    /** A document is saved under the name it was sent with. */
    @Test
    fun documentsKeepTheirRealName() {
        val download = File("/data/media/3EB0C5A1.bin")
        assertEquals(SaveTarget("Q3 report.docx", docx), saveTarget("Q3 report.docx", docx, download))
        assertEquals(SaveTarget("Notes.PDF", "application/pdf"), saveTarget("Notes.PDF", "application/pdf", download))
        // No extension, or one that isn't a type's: the type's is added.
        assertEquals(SaveTarget("Q3 report.docx", docx), saveTarget("Q3 report", docx, download))
        assertEquals(SaveTarget("minutes.final.pdf", "application/pdf"), saveTarget("minutes.final", "application/pdf", download))
        // An extension naming another type is kept, and saved as that type.
        assertEquals(SaveTarget("table.csv", "text/csv"), saveTarget("table.csv", "text/plain", download))
        // A type that says nothing leaves the name alone.
        assertEquals(SaveTarget("backup.dat", "application/octet-stream"), saveTarget("backup.dat", "application/octet-stream", download))
        assertEquals(SaveTarget("book.epub", "application/epub+zip"), saveTarget("book.epub", null, download))
    }

    @Test
    fun aSentNameIsCleanedFirst() {
        assertEquals(
            SaveTarget("a_b report.pdf", "application/pdf"),
            saveTarget("a/b‮ report.pdf", "application/pdf", File("x.bin")),
        )
    }

    /** Without a name, Photon's download is saved with its type's extension in place of ".bin". */
    @Test
    fun downloadsWithoutANameTakeTheirTypesExtension() {
        assertEquals(SaveTarget("3EB0C5A1.docx", docx), saveTarget(null, docx, File("/data/media/3EB0C5A1.bin")))
        assertEquals(SaveTarget("sig_123.odt", "application/vnd.oasis.opendocument.text"), saveTarget(null, "application/vnd.oasis.opendocument.text", File("sig_123")))
        assertEquals(SaveTarget("photo.jpg", "image/jpeg"), saveTarget(null, "image/jpeg", File("photo.jpg")))
        assertEquals(SaveTarget("3EB0.bin", "application/octet-stream"), saveTarget(null, null, File("3EB0.bin")))
    }
}
