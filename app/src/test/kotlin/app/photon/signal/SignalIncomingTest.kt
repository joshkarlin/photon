package app.photon.signal

import okio.ByteString.Companion.toByteString
import org.signal.core.models.ServiceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignalIncomingTest {

    private val author = "00000000-0000-4000-8000-000000000001"

    @Test
    fun serviceIdStringPrefersBinaryAndPassesThroughUnparseableStrings() {
        val binary = ServiceId.ACI.parseOrNull(author)!!.toByteArray().toByteString()
        assertEquals(author, serviceIdString(null, binary))
        assertEquals(author, serviceIdString("ignored", binary))
        assertEquals(author, serviceIdString(author, null))
        assertEquals("not-a-uuid", serviceIdString("not-a-uuid", null))
        assertNull(serviceIdString(null, null))
    }
}
