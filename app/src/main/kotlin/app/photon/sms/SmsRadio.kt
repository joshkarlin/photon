package app.photon.sms

import android.app.PendingIntent
import android.content.Context
import android.telephony.SmsManager

/** Where SMS go out. */
internal interface SmsRadio {
    /** [text] in the parts it's sent as. */
    fun divide(text: String): List<String>

    /**
     * Send [text], divided into [parts], to [address]; each part's result
     * comes back through its intent in [sentIntents].
     */
    fun send(address: String, text: String, parts: List<String>, sentIntents: List<PendingIntent>)
}

/** The phone's own SMS, through SmsManager. */
internal class SystemSmsRadio(private val context: Context) : SmsRadio {
    private val manager: SmsManager get() = context.getSystemService(SmsManager::class.java)

    override fun divide(text: String): List<String> = manager.divideMessage(text)

    override fun send(address: String, text: String, parts: List<String>, sentIntents: List<PendingIntent>) {
        if (parts.size == 1) {
            manager.sendTextMessage(address, null, text, sentIntents.single(), null)
        } else {
            manager.sendMultipartTextMessage(address, null, ArrayList(parts), ArrayList(sentIntents), null)
        }
    }
}
