package app.photon.nav

import kotlin.test.Test
import kotlin.test.assertEquals

class WhatsAppRouteTest {

    @Test
    fun onlyAMissingSessionGoesToPairing() {
        assertEquals(Routes.WHATSAPP_PAIRING, whatsappRoute("logged_out"))
    }

    @Test
    fun aPairedAccountGoesToItsChatsWhateverItsConnection() {
        for (state in listOf("connected", "connecting", "disconnected", null)) {
            assertEquals(Routes.WHATSAPP_CHATS, whatsappRoute(state), "state $state")
        }
    }
}
