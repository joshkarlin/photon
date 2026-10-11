package app.photon.data.model

import android.provider.Telephony
import app.photon.data.db.whatsAppStatus
import app.photon.data.model.MessageStatus.DELETED
import app.photon.data.model.MessageStatus.DELIVERED
import app.photon.data.model.MessageStatus.FAILED
import app.photon.data.model.MessageStatus.READ
import app.photon.data.model.MessageStatus.SEEN
import app.photon.data.model.MessageStatus.SENDING
import app.photon.data.model.MessageStatus.SENT
import app.photon.data.model.MessageStatus.UNREAD
import app.photon.signal.db.signalStatus
import app.photon.sms.smsStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Each platform's stored status strings, in the one vocabulary. */
class MessageStatusTest {

    private val outgoing = mapOf(
        "sending" to SENDING, "sent" to SENT, "delivered" to DELIVERED,
        "read" to READ, "failed" to FAILED, "deleted" to DELETED,
    )

    @Test
    fun whatsAppAndSignalShareTheOutgoingStrings() {
        for ((stored, status) in outgoing) {
            assertEquals(status, whatsAppStatus(stored, isFromMe = true), "WhatsApp $stored")
            assertEquals(status, signalStatus(stored, isFromMe = true), "Signal $stored")
        }
    }

    @Test
    fun anIncomingWhatsAppMessageIsUnreadUntilRead() {
        // The bridge stores live incoming messages (and unread call rows) as "sent".
        assertEquals(UNREAD, whatsAppStatus("sent", isFromMe = false))
        assertEquals(READ, whatsAppStatus("read", isFromMe = false))
        assertEquals(DELETED, whatsAppStatus("deleted", isFromMe = false))
    }

    @Test
    fun signalKeepsSeenBetweenUnreadAndRead() {
        assertEquals(UNREAD, signalStatus("received", isFromMe = false))
        assertEquals(SEEN, signalStatus("seen", isFromMe = false))
        assertEquals(READ, signalStatus("read", isFromMe = false))
        assertEquals(DELETED, signalStatus("deleted", isFromMe = false))
    }

    @Test
    fun smsStatusComesFromTheProviderTypeAndPhotonsReadMark() {
        assertEquals(SENT, smsStatus(Telephony.Sms.MESSAGE_TYPE_SENT, read = true, isNewerThanLastRead = true))
        assertEquals(FAILED, smsStatus(Telephony.Sms.MESSAGE_TYPE_FAILED, read = true, isNewerThanLastRead = true))
        for (type in listOf(Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED)) {
            assertEquals(SENDING, smsStatus(type, read = true, isNewerThanLastRead = true))
        }
        val inbox = Telephony.Sms.MESSAGE_TYPE_INBOX
        assertEquals(UNREAD, smsStatus(inbox, read = false, isNewerThanLastRead = true))
        assertEquals(READ, smsStatus(inbox, read = false, isNewerThanLastRead = false), "opened in Photon since")
        assertEquals(READ, smsStatus(inbox, read = true, isNewerThanLastRead = true), "read in the provider")
    }

    @Test
    fun onlySendingAndFailedAreUnsent() {
        assertEquals(setOf(SENDING, FAILED), MessageStatus.entries.filter { it.isUnsent }.toSet())
        assertTrue(FAILED.isUnsent)
        assertFalse(DELIVERED.isUnsent)
    }
}
