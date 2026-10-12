package app.photon.signal.receiver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.photon.signal.SignalCredentials
import app.photon.signal.SignalNames
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.store.SignalProtocolDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Records what the receiver would have shown or cleared, in order. */
internal class RecordingNotifier : SignalNotifier {
    val events = mutableListOf<String>()

    /** Conversations whose message notification is up (not dismissed). */
    val showing = mutableSetOf<String>()

    /** Who each ringing or missed call notification named, in order. */
    val callers = mutableListOf<String>()

    override fun isShowingMessage(conversationJid: String) = conversationJid in showing

    /** A new message is "message:…"; a correction of the showing one is "update:…". */
    override fun showMessage(title: String, body: String?, conversationJid: String, update: Boolean) {
        events += "${if (update) "update" else "message"}:$conversationJid:$title:$body"
    }

    override fun showIncomingCall(callerName: String, conversationJid: String, isVideo: Boolean) {
        events += "ringing:$conversationJid"
        callers += callerName
    }

    override fun showMissedCall(callerName: String, conversationJid: String, isVideo: Boolean) {
        events += "missed:$conversationJid"
        callers += callerName
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
    val protocolDb = SignalProtocolDatabase(context)
    val credentials = SignalCredentials(protocolDb).apply {
        aciString = localAci
        storedDeviceId = localDeviceId
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val notifier = RecordingNotifier()
    /** This device's address book: phone → LP3 contact name. */
    val addressBook = mutableMapOf<String, String>()
    private val lp3: (String) -> String? = { addressBook[it] }
    val names = SignalNames(db, lp3) { localAci }
    val contactNames = ContactNames(db, Any(), notifier, profileFetcher = { null })
    val groupMetadata = GroupMetadata(db, scope, { null }, SessionPings(context, credentials, db, scope) { false })

    fun close() {
        scope.cancel()
        db.close()
        protocolDb.close()
    }
}

/**
 * The data and sync handlers over one [ReceiverFixture], with no network, as
 * the receiver wires them: what arrives from other people and from the
 * user's other devices.
 */
internal class Handlers(val fx: ReceiverFixture, clock: () -> Long = System::currentTimeMillis) {
    val earlyReads = app.photon.signal.EarlyReadCache()
    val notifications = MessageNotifications(fx.db, fx.names, fx.notifier, clock)
    val remoteEdits = RemoteEdits(fx.db, notifications)
    val calls = CallHandler(fx.credentials, fx.db, fx.contactNames, fx.names, fx.notifier, schedule = { _, _ -> }, clock = clock)
    val data = DataMessageHandler(
        fx.db, fx.contactNames, fx.groupMetadata, calls, remoteEdits,
        Attachments(fx.context, fx.db) { null }, earlyReads, notifications, fx.scope,
    )
    val sync = SyncMessageHandler(
        fx.credentials, app.photon.signal.store.PhotonIdentityKeyStore(fx.protocolDb), fx.db, fx.contactNames,
        fx.groupMetadata, calls, remoteEdits, ContactsSync(fx.context, fx.db, { null }, {}), earlyReads, fx.notifier,
        app.photon.signal.storage.StorageMuteSync(fx.credentials, fx.db, fx.scope, { null }, { false }),
        fx.scope,
    )

    /** Envelope metadata for a message from [aci] (in [groupId]'s group, if given). */
    fun from(aci: String, groupId: ByteArray? = null) = org.whispersystems.signalservice.api.crypto.EnvelopeMetadata(
        org.signal.core.models.ServiceId.ACI.parseOrNull(aci)!!, null, 1, true, groupId,
        org.signal.core.models.ServiceId.ACI.parseOrNull(fx.credentials.aciString!!)!!, 0,
    )
}
