package app.photon.signal.receiver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.photon.signal.AndroidContactResolver
import app.photon.signal.SignalCredentials
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.store.SignalProtocolDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Records what the receiver would have shown or cleared, in order. */
internal class RecordingNotifier : SignalNotifier {
    val events = mutableListOf<String>()

    override fun showMessage(title: String, body: String?, conversationJid: String) {
        events += "message:$conversationJid:$title:$body"
    }

    override fun showIncomingCall(callerName: String, conversationJid: String, isVideo: Boolean) {
        events += "ringing:$conversationJid"
    }

    override fun showMissedCall(callerName: String, conversationJid: String, isVideo: Boolean) {
        events += "missed:$conversationJid"
    }

    override fun cancelIncomingCall(conversationJid: String) {
        events += "cancelRinging:$conversationJid"
    }

    override fun cancelConversation(conversationJid: String) {
        events += "cancel:$conversationJid"
    }
}

/**
 * Real databases (Robolectric SQLite) and the receiver's naming/group
 * helpers, with no network: the profile fetcher and group manager are absent,
 * as before the first connect.
 */
internal class ReceiverFixture(localAci: String, localDeviceId: Int = 3) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db = SignalMessageDatabase(context)
    private val protocolDb = SignalProtocolDatabase(context)
    val credentials = SignalCredentials(protocolDb).apply {
        aciString = localAci
        storedDeviceId = localDeviceId
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val notifier = RecordingNotifier()
    private val resolver = AndroidContactResolver(context)
    val contactNames = ContactNames(db, resolver, notifier, profileFetcher = { null })
    val groupMetadata = GroupMetadata(db, resolver, scope, { null }, SessionPings(context, credentials, db, scope))

    fun close() {
        scope.cancel()
        db.close()
        protocolDb.close()
    }
}
