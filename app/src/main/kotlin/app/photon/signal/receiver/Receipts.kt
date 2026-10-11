package app.photon.signal.receiver

import android.util.Log
import app.photon.signal.SignalCredentials
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.updateStatusByIdPrefix
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.internal.push.Envelope

private const val TAG = SignalMessageReceiver.TAG

/** Delivery and read receipts for messages this account sent. */
internal class Receipts(
    private val credentials: SignalCredentials,
    private val messageDb: SignalMessageDatabase,
) {
    /**
     * Contentless envelope — the server's own delivery receipt. There's
     * nothing to decrypt; the envelope itself is the signal that our message
     * (sent at clientTimestamp) reached the recipient's server queue.
     */
    fun processContentlessEnvelope(envelope: Envelope) {
        val myAci = credentials.aciString
        val ts = envelope.clientTimestamp
        if (envelope.type == Envelope.Type.SERVER_DELIVERY_RECEIPT && myAci != null && ts != null) {
            messageDb.updateStatusByIdPrefix("${myAci}_$ts", "delivered")
        } else {
            Log.d(TAG, "Contentless envelope (type=${envelope.type}); acking")
        }
    }

    fun processReceipt(
        receipt: org.whispersystems.signalservice.internal.push.ReceiptMessage,
        metadata: EnvelopeMetadata,
    ) {
        val timestamps = receipt.timestamp
        val type = receipt.type
        val myAci = credentials.aciString ?: return

        val newStatus = when (type) {
            org.whispersystems.signalservice.internal.push.ReceiptMessage.Type.READ -> "read"
            org.whispersystems.signalservice.internal.push.ReceiptMessage.Type.DELIVERY -> "delivered"
            else -> return
        }

        for (ts in timestamps) {
            // Receipts only carry the original sent timestamp; match our rows
            // by the stable "{aci}_{ts}" prefix (ids carry a random suffix).
            messageDb.updateStatusByIdPrefix("${myAci}_$ts", newStatus)
        }
    }
}
