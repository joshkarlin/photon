package app.photon.ui.shared.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.photon.ui.shared.PickedDocumentProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A picked file's own name, read from its provider and made safe to send. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PickedFileNameTest {
    private val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver

    @Before
    fun setUp() = PickedDocumentProvider.install()

    @After
    fun tearDown() = PickedDocumentProvider.reset()

    @Test
    fun theProvidersDisplayNameIsRead() {
        PickedDocumentProvider.displayName = "Q1 report.pdf"
        assertEquals("Q1 report.pdf", pickedFileName(resolver, PickedDocumentProvider.DOCUMENT))
    }

    @Test
    fun noNameOrARefusingProviderGivesNone() {
        assertNull(pickedFileName(resolver, PickedDocumentProvider.DOCUMENT))
        PickedDocumentProvider.displayName = "Q1 report.pdf"
        PickedDocumentProvider.refuseQueries = true
        assertNull(pickedFileName(resolver, PickedDocumentProvider.DOCUMENT))
    }

    @Test
    fun ordinaryNamesAreKept() {
        listOf("Q1 report.pdf", "Lease (signed) – final.docx", "договор.pdf", "見積書.xlsx", "notes", ".env").forEach {
            assertEquals(it, sanitizeFileName(it))
        }
    }

    @Test
    fun pathSeparatorsCantMakeAPath() {
        assertEquals("Invoice 03_2026.pdf", sanitizeFileName("Invoice 03/2026.pdf"))
        assertEquals(".._.._etc_passwd", sanitizeFileName("../../etc/passwd"))
        assertEquals("C:_Users_me_cv.pdf", sanitizeFileName("C:\\Users\\me\\cv.pdf"))
    }

    @Test
    fun controlAndDirectionCharactersGo() {
        assertEquals("report.pdf", sanitizeFileName("re\u0000po\nrt\t.pdf\u007F"))
        // A right-to-left override would show "invoice‮fdp.exe" as "invoiceexe.pdf".
        assertEquals("invoicefdp.exe", sanitizeFileName("invoice\u202Efdp.exe"))
        assertEquals("a b.pdf", sanitizeFileName("a\u2066 b\u2069.pdf\u2028"))
    }

    @Test
    fun blankNamesAreNone() {
        listOf("", "   ", "\u0000\n", ".", "..", "\u202E").forEach { assertNull(sanitizeFileName(it), "\"$it\"") }
    }

    @Test
    fun longNamesAreCutKeepingTheExtension() {
        val cut = sanitizeFileName("a".repeat(300) + ".pdf")!!
        assertEquals(MAX_FILE_NAME_LENGTH, cut.length)
        assertTrue(cut.endsWith("a.pdf"), cut)

        // No short extension to keep: just the first characters.
        assertEquals("b".repeat(MAX_FILE_NAME_LENGTH), sanitizeFileName("b".repeat(150) + ".verylongextension"))
        assertEquals("b".repeat(MAX_FILE_NAME_LENGTH), sanitizeFileName("b".repeat(300)))
    }

    @Test
    fun cuttingNeverSplitsACharacter() {
        val emoji = "\uD83D\uDCC4" // 📄, two UTF-16 units
        val cut = sanitizeFileName(emoji.repeat(150) + ".pdf")!!
        assertEquals(MAX_FILE_NAME_LENGTH, cut.codePointCount(0, cut.length))
        assertTrue(cut.removeSuffix(".pdf").chunked(2).all { it == emoji }, cut)
    }
}
