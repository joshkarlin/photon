package app.photon.signal

import org.junit.Assert.assertEquals
import org.junit.Test
import org.whispersystems.signalservice.api.crypto.AttachmentCipherStreamUtil
import org.whispersystems.signalservice.internal.crypto.PaddingInputStream

class SignalAttachmentUploadTest {
    @Test
    fun uploadFormUsesPaddedCiphertextLength() {
        assertEquals(592L, signalAttachmentUploadLength(1L))
        val fileLength = 4096L
        val expected = AttachmentCipherStreamUtil.getCiphertextLength(
            PaddingInputStream.getPaddedSize(fileLength),
        )
        assertEquals(expected, signalAttachmentUploadLength(fileLength))
        assertEquals(592L, signalAttachmentUploadLength(0L))
    }

    @Test
    fun parsesRestUploadFormWithHeaders() {
        val form = parseSignalAttachmentUploadForm(
            """{"cdn":3,"key":"cdn-key","headers":{"Authorization":"token","X-Test":"value"},"signedUploadLocation":"https://cdn3.signal.org/upload"}""",
        )
        assertEquals(3, form.cdn)
        assertEquals("cdn-key", form.key)
        assertEquals("token", form.headers["Authorization"])
        assertEquals("value", form.headers["X-Test"])
        assertEquals("https://cdn3.signal.org/upload", form.signedUploadLocation)
    }
}
