package app.photon.service

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The running platform sessions, for the UI. [PhotonService] publishes them
 * once it has built them and clears them when it stops. They're Compose
 * state: the activity composes before the service has started, and a screen
 * that read null then recomposes onto the session when it appears.
 */
object Sessions {
    var whatsapp: WhatsAppSession? by mutableStateOf(null)
        private set
    var signal: SignalSession? by mutableStateOf(null)
        private set
    var sms: SmsSession? by mutableStateOf(null)
        private set

    internal fun publish(whatsapp: WhatsAppSession, signal: SignalSession, sms: SmsSession) {
        this.whatsapp = whatsapp
        this.signal = signal
        this.sms = sms
    }

    internal fun clear() {
        whatsapp = null
        signal = null
        sms = null
    }
}
