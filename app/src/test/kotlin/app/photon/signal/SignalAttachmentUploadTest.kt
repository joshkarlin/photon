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
}
