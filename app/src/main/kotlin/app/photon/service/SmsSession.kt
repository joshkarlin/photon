package app.photon.service

import android.content.Context
import app.photon.sms.SmsRepository
import kotlinx.coroutines.CoroutineScope

/**
 * SMS for as long as the service runs: the system SMS store, read through
 * [repository]. While started it hears the radio's results for sends.
 */
class SmsSession(context: Context, scope: CoroutineScope) {
    val repository = SmsRepository(context, scope)

    fun start() = repository.startSendResults()

    fun stop() = repository.stopSendResults()
}
