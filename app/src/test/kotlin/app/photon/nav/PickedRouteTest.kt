package app.photon.nav

import app.photon.data.model.Platform
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The platform picker opens a platform's chats unless it must be linked first. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PickedRouteTest {

    @Test
    fun onlyAMissingWhatsAppSessionGoesToPairing() {
        assertEquals(Routes.WHATSAPP_PAIRING, pickedRoute(Platform.WHATSAPP, "logged_out", signalLinked = true))
        for (state in listOf("connected", "connecting", "disconnected", null)) {
            assertEquals(Routes.chats(Platform.WHATSAPP), pickedRoute(Platform.WHATSAPP, state, signalLinked = false), "state $state")
        }
    }

    @Test
    fun anUnlinkedSignalGoesToPairing() {
        assertEquals(Routes.SIGNAL_PAIRING, pickedRoute(Platform.SIGNAL, "connected", signalLinked = false))
        assertEquals(Routes.chats(Platform.SIGNAL), pickedRoute(Platform.SIGNAL, "logged_out", signalLinked = true))
    }

    @Test
    fun allChatsAndSmsNeedNoPairing() {
        assertEquals("chats", pickedRoute(null, "logged_out", signalLinked = false))
        assertEquals("chats?platform=SMS", pickedRoute(Platform.SMS, "logged_out", signalLinked = false))
    }
}
