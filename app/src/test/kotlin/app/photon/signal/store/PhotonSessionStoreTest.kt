package app.photon.signal.store

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.SessionRecord
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PhotonSessionStoreTest {

    @Test
    fun sessionWithoutSenderChainIsNotOfferedForEncryption() {
        val db = SignalProtocolDatabase(ApplicationProvider.getApplicationContext())
        try {
            val store = PhotonSessionStore(db)
            val address = SignalProtocolAddress("00000000-0000-4000-8000-000000000001", 4)
            store.storeSession(address, SessionRecord())

            assertFalse(store.containsSession(address))
            assertEquals(emptyList(), store.getSubDeviceSessions(address.name))
        } finally {
            db.close()
        }
    }
}
