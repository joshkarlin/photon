package app.photon.signal.receiver

import app.photon.signal.EarlyReadCache
import app.photon.signal.storage.ManifestFetch
import app.photon.signal.storage.RecordsFetch
import app.photon.signal.storage.StorageMuteSync
import app.photon.signal.storage.StorageReader
import app.photon.signal.storage.storageKeyFrom
import app.photon.signal.store.PhotonIdentityKeyStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.AccountEntropyPool
import org.signal.core.models.ServiceId
import org.signal.core.models.storageservice.StorageKey
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.api.storage.SignalStorageManifest
import org.whispersystems.signalservice.api.storage.StorageId
import org.whispersystems.signalservice.internal.push.SyncMessage
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Keys and storage-change sync messages reach the mute sync, from the account's own devices only. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class StorageSyncMessagesTest {

    private val local = "00000000-0000-4000-8000-00000000000a"
    private val stranger = "00000000-0000-4000-8000-00000000a11c"

    private lateinit var fx: ReceiverFixture
    private lateinit var handler: SyncMessageHandler
    private var manifestReads = 0

    @Before
    fun setUp() {
        fx = ReceiverFixture(local)
        val reader = object : StorageReader {
            override fun manifest(key: StorageKey, knownVersion: Long?): ManifestFetch {
                manifestReads++
                return ManifestFetch.NotFound
            }

            override fun records(key: StorageKey, manifest: SignalStorageManifest, ids: List<StorageId>) =
                RecordsFetch.Read(emptyList())
        }
        val storage = StorageMuteSync(fx.credentials, fx.db, fx.scope, { reader }, { true }, keysRequestDelayMs = 0)
        val notifications = MessageNotifications(fx.db, fx.names, fx.notifier)
        val calls = CallHandler(fx.credentials, fx.db, fx.contactNames, fx.names, fx.notifier, schedule = { _, _ -> })
        handler = SyncMessageHandler(
            fx.credentials, PhotonIdentityKeyStore(fx.protocolDb), fx.db, fx.contactNames, fx.groupMetadata,
            calls, RemoteEdits(fx.db, notifications), ContactsSync(fx.context, fx.db, { null }, {}),
            EarlyReadCache(), fx.notifier, storage, fx.scope,
        )
    }

    @After
    fun tearDown() = fx.close()

    private fun from(aci: String) =
        EnvelopeMetadata(ServiceId.ACI.parseOrNull(aci)!!, null, 1, true, null, ServiceId.ACI.parseOrNull(local)!!, 0)

    @Test
    fun keysFromThePrimaryAreKept() {
        val pool = AccountEntropyPool.generate().value

        handler.processSyncMessage(SyncMessage(keys = SyncMessage.Keys(accountEntropyPool = pool)), from(local))

        assertContentEquals(storageKeyFrom(pool), fx.credentials.storageKey)
        assertEquals(1, manifestReads)
    }

    @Test
    fun keysFromAnotherAccountAreIgnored() {
        val pool = AccountEntropyPool.generate().value

        handler.processSyncMessage(SyncMessage(keys = SyncMessage.Keys(accountEntropyPool = pool)), from(stranger))

        assertNull(fx.credentials.storageKey)
    }

    @Test
    fun aStorageManifestFetchLatestReadsStorage() {
        fx.credentials.storageKey = ByteArray(32) { 1 }
        val storageChanged = SyncMessage(fetchLatest = SyncMessage.FetchLatest(type = SyncMessage.FetchLatest.Type.STORAGE_MANIFEST))

        handler.processSyncMessage(storageChanged, from(local))
        handler.processSyncMessage(storageChanged, from(stranger))
        handler.processSyncMessage(
            SyncMessage(fetchLatest = SyncMessage.FetchLatest(type = SyncMessage.FetchLatest.Type.LOCAL_PROFILE)),
            from(local),
        )

        assertEquals(1, manifestReads)
    }
}
