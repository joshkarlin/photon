package app.photon.signal.storage

import app.photon.signal.SignalGroupV2Manager
import app.photon.signal.db.getContact
import app.photon.signal.db.getConversation
import app.photon.signal.db.keepMutedChatsArchived
import app.photon.signal.db.storageManifestVersion
import app.photon.signal.db.storageRecordIds
import app.photon.signal.db.updateContactPhone
import app.photon.signal.db.upsertConversation
import app.photon.signal.receiver.ReceiverFixture
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.AccountEntropyPool
import org.signal.core.models.storageservice.StorageKey
import org.whispersystems.signalservice.api.storage.SignalStorageManifest
import org.whispersystems.signalservice.api.storage.SignalStorageRecord
import org.whispersystems.signalservice.api.storage.StorageId
import org.whispersystems.signalservice.internal.storage.protos.AccountRecord
import org.whispersystems.signalservice.internal.storage.protos.ContactRecord
import org.whispersystems.signalservice.internal.storage.protos.GroupV2Record
import org.whispersystems.signalservice.internal.storage.protos.StorageRecord
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reading mutes from the storage service: the key, the manifest-version
 * gate, reading only new records, and failures that leave things as they
 * were. The network is a fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class StorageMuteSyncTest {

    private val local = "00000000-0000-4000-8000-00000000000a"
    private val alice = "00000000-0000-4000-8000-00000000a11c"
    private val bob = "00000000-0000-4000-8000-000000000b0b"
    private val pniUuid = "00000000-0000-4000-8000-0000000000b1"
    private val pni = "PNI:$pniUuid"
    private val phone = "+15550001111"
    private val forever = Long.MAX_VALUE

    /** The storage service: a manifest, its records, and the calls made of it. */
    private class FakeStorage : StorageReader {
        var manifest: SignalStorageManifest? = null
        var records = mapOf<StorageId, SignalStorageRecord>()
        var manifestFailure: ManifestFetch? = null
        var recordsFailure: RecordsFetch? = null
        var onManifest: () -> Unit = {}
        val manifestCalls = mutableListOf<Long?>()
        val recordCalls = mutableListOf<List<StorageId>>()
        val keys = mutableListOf<ByteArray>()

        override fun manifest(key: StorageKey, knownVersion: Long?): ManifestFetch {
            keys += key.serialize()
            manifestCalls += knownVersion
            onManifest()
            manifestFailure?.let { return it }
            val current = manifest ?: return ManifestFetch.NotFound
            return if (current.version == knownVersion) ManifestFetch.Unchanged else ManifestFetch.Changed(current)
        }

        override fun records(key: StorageKey, manifest: SignalStorageManifest, ids: List<StorageId>): RecordsFetch {
            recordCalls += ids
            recordsFailure?.let { return it }
            return RecordsFetch.Read(ids.mapNotNull { records[it] })
        }

        fun publish(version: Long, vararg records: SignalStorageRecord) {
            manifest = SignalStorageManifest(version, 1, null, records.map { it.id })
            this.records = records.associateBy { it.id }
        }
    }

    private lateinit var fx: ReceiverFixture
    private lateinit var storage: FakeStorage
    private lateinit var sync: StorageMuteSync
    private var connected = true
    private var keyRequests = 0
    private var keyRequestsGoOut = true

    @Before
    fun setUp() {
        fx = ReceiverFixture(local)
        storage = FakeStorage()
        sync = StorageMuteSync(
            fx.credentials, fx.db, fx.scope,
            reader = { if (connected) storage else null },
            requestKeys = { keyRequests++; keyRequestsGoOut },
            keysRequestDelayMs = 0,
        )
        for (jid in listOf(alice, bob)) fx.db.upsertConversation(jid = jid, name = null, isGroup = false)
    }

    @After
    fun tearDown() = fx.close()

    private var nextId = 0

    private fun contact(record: ContactRecord) =
        SignalStorageRecord(StorageId.forContact(byteArrayOf((++nextId).toByte())), StorageRecord(contact = record))

    private fun group(masterKey: ByteArray, mutedUntil: Long) = SignalStorageRecord(
        StorageId.forGroupV2(byteArrayOf((++nextId).toByte())),
        StorageRecord(groupV2 = GroupV2Record(masterKey = masterKey.toByteString(), mutedUntilTimestamp = mutedUntil)),
    )

    private fun giveKey() {
        fx.credentials.storageKey = ByteArray(32) { 1 }
    }

    private fun muted(jid: String) = fx.db.getConversation(jid)?.isMuted

    @Test
    fun withoutAKeyItAsksThePrimaryOnce() {
        sync.onConnected()
        sync.onConnected()
        sync.onStorageChanged()

        assertEquals(1, keyRequests)
        assertEquals(emptyList<Long?>(), storage.manifestCalls)
    }

    @Test
    fun aKeysRequestThatDidntGoOutIsRetriedOnTheNextConnect() {
        keyRequestsGoOut = false
        sync.onConnected()
        keyRequestsGoOut = true
        sync.onConnected()
        sync.onConnected()

        assertEquals(2, keyRequests)
    }

    @Test
    fun aKeysSyncStoresTheKeyAndReads() {
        val pool = AccountEntropyPool.generate()
        storage.publish(1, contact(ContactRecord(aci = alice, mutedUntilTimestamp = forever)))

        sync.onKeys(pool.value)

        assertContentEquals(storageKeyFrom(pool.value), fx.credentials.storageKey)
        assertContentEquals(storageKeyFrom(pool.value), storage.keys.single())
        assertEquals(true, muted(alice))

        // The same key again changes nothing and reads nothing.
        sync.onKeys(pool.value)
        assertEquals(1, storage.manifestCalls.size)
    }

    @Test
    fun aKeysSyncWithoutAPoolIsIgnored() {
        sync.onKeys(null)
        sync.onKeys("not a pool")

        assertNull(fx.credentials.storageKey)
        assertEquals(emptyList<Long?>(), storage.manifestCalls)
    }

    /** A new key: the manifest is read whole whatever its version, so nothing read under the old key is trusted. */
    @Test
    fun aNewKeyReadsTheWholeManifestAgain() {
        giveKey()
        storage.publish(5, contact(ContactRecord(aci = alice, mutedUntilTimestamp = forever)))
        sync.onStorageChanged()
        assertEquals(5, fx.db.storageManifestVersion())

        sync.onKeys(AccountEntropyPool.generate().value)

        assertEquals(listOf<Long?>(null, null), storage.manifestCalls)
        assertEquals(true, muted(alice))
    }

    /** Only a changed manifest is read, and of it only the records that are new. */
    @Test
    fun readsOnlyWhatChanged() {
        giveKey()
        val aliceMuted = contact(ContactRecord(aci = alice, mutedUntilTimestamp = forever))
        val bobPlain = contact(ContactRecord(aci = bob))
        storage.publish(1, aliceMuted, bobPlain)

        assertEquals(StorageMuteSync.Outcome.UPDATED, sync.read())
        assertEquals(listOf(listOf(aliceMuted.id, bobPlain.id)), storage.recordCalls)
        assertEquals(true, muted(alice))
        assertEquals(false, muted(bob))

        // Same version: the manifest isn't even downloaded again.
        assertEquals(StorageMuteSync.Outcome.UP_TO_DATE, sync.read())
        assertEquals(listOf<Long?>(null, 1), storage.manifestCalls)
        assertEquals(1, storage.recordCalls.size)

        // Alice unmuted, Bob muted for an hour: two replaced records.
        val inAnHourMs = System.currentTimeMillis() + 3_600_000
        val aliceUnmuted = contact(ContactRecord(aci = alice))
        val bobMuted = contact(ContactRecord(aci = bob, mutedUntilTimestamp = inAnHourMs))
        storage.publish(2, aliceUnmuted, bobMuted)

        assertEquals(StorageMuteSync.Outcome.UPDATED, sync.read())
        assertEquals(listOf(aliceUnmuted.id, bobMuted.id), storage.recordCalls.last())
        assertEquals(false, muted(alice))
        assertEquals(true, muted(bob))
        assertEquals(2, fx.db.storageManifestVersion())
        assertEquals(2, fx.db.storageRecordIds().size)

        // A change elsewhere (an account record, say): nothing new to read.
        storage.publish(3, aliceUnmuted, bobMuted)
        assertEquals(StorageMuteSync.Outcome.UPDATED, sync.read())
        assertEquals(2, storage.recordCalls.size)
        assertEquals(3, fx.db.storageManifestVersion())
    }

    @Test
    fun onlyContactGroupAndAccountRecordsAreRequested() {
        giveKey()
        val aliceMuted = contact(ContactRecord(aci = alice, mutedUntilTimestamp = forever))
        val account = StorageId.forAccount(byteArrayOf(99))
        storage.manifest = SignalStorageManifest(
            1, 1, null, listOf(aliceMuted.id, account, StorageId.forStoryDistributionList(byteArrayOf(98))),
        )
        storage.records = mapOf(aliceMuted.id to aliceMuted)

        sync.read()

        assertEquals(listOf(listOf(aliceMuted.id, account)), storage.recordCalls)
    }

    /** The group's jid comes from its master key exactly as for an incoming group message. */
    @Test
    fun aGroupRecordMutesTheGroupChat() {
        giveKey()
        val masterKey = ByteArray(32) { (it * 7).toByte() }
        val groupJid = SignalGroupV2Manager.jidFromMasterKey(masterKey)
        fx.db.upsertConversation(jid = groupJid, name = "Climbing", isGroup = true)
        storage.publish(1, group(masterKey, forever))

        sync.read()

        assertEquals(true, muted(groupJid))
    }

    /**
     * Muted while Photon knows them only by PNI: the PNI keeps their number,
     * and once a contacts sync maps it to the ACI the startup merge moves
     * the mute to the ACI chat.
     */
    @Test
    fun aPniOnlyMuteReachesTheAciChatAfterAContactsSync() {
        giveKey()
        storage.publish(1, contact(ContactRecord(pni = pniUuid, e164 = phone, mutedUntilTimestamp = forever)))

        sync.read()
        assertEquals(phone, fx.db.getContact(pni)?.phone)
        assertEquals(false, muted(alice))

        fx.db.updateContactPhone(alice, phone) // the contacts sync
        fx.contactNames.mergeResolvableServiceIds()

        assertEquals(true, muted(alice))
    }

    /** Known by number already: the record maps straight to the ACI chat. */
    @Test
    fun aPniOnlyRecordForAKnownNumberMutesTheAciChat() {
        giveKey()
        fx.db.updateContactPhone(alice, phone)
        storage.publish(1, contact(ContactRecord(pni = pniUuid, e164 = phone, mutedUntilTimestamp = forever)))

        sync.read()

        assertEquals(true, muted(alice))
    }

    @Test
    fun noManifestYetIsNotAnError() {
        giveKey()

        assertEquals(StorageMuteSync.Outcome.NO_MANIFEST, sync.read())
        assertNull(fx.db.storageManifestVersion())
    }

    @Test
    fun aFailedReadChangesNothingAndIsTriedAgainLater() {
        giveKey()
        storage.publish(1, contact(ContactRecord(aci = alice, mutedUntilTimestamp = forever)))

        storage.manifestFailure = ManifestFetch.Failed("HTTP 403")
        assertEquals(StorageMuteSync.Outcome.FAILED, sync.read())
        storage.manifestFailure = null
        storage.recordsFailure = RecordsFetch.Failed("network: timeout")
        assertEquals(StorageMuteSync.Outcome.FAILED, sync.read())
        assertNull(fx.db.storageManifestVersion())
        assertEquals(false, muted(alice))

        storage.recordsFailure = null
        assertEquals(StorageMuteSync.Outcome.UPDATED, sync.read())
        assertEquals(true, muted(alice))
        assertEquals(0, keyRequests)
    }

    @Test
    fun aKeyThatNoLongerDecryptsAsksThePrimaryOnce() {
        giveKey()
        storage.manifestFailure = ManifestFetch.BadKey

        assertEquals(StorageMuteSync.Outcome.BAD_KEY, sync.read())
        assertEquals(StorageMuteSync.Outcome.BAD_KEY, sync.read())
        storage.manifestFailure = null
        storage.publish(1, contact(ContactRecord(aci = alice)))
        storage.recordsFailure = RecordsFetch.BadKey
        assertEquals(StorageMuteSync.Outcome.BAD_KEY, sync.read())

        assertEquals(1, keyRequests)
    }

    /** A Signal reset while a read is on the network: it mustn't write the old account's mutes. */
    @Test
    fun aReadOverlappingAResetStoresNothing() {
        giveKey()
        storage.publish(1, contact(ContactRecord(aci = alice, mutedUntilTimestamp = forever)))
        storage.onManifest = { fx.protocolDb.deleteAll() }

        assertEquals(StorageMuteSync.Outcome.KEY_CHANGED, sync.read())

        assertNull(fx.db.storageManifestVersion())
        assertEquals(emptySet(), fx.db.storageRecordIds())
        assertEquals(false, muted(alice))
    }

    @Test
    fun disconnectedReadsNothing() {
        giveKey()
        connected = false

        assertEquals(StorageMuteSync.Outcome.DISCONNECTED, sync.read())
    }

    /** A storage change announced during a read runs one more read after it, not a loop. */
    @Test
    fun changesDuringAReadCoalesceIntoOneMore() {
        giveKey()
        storage.publish(1, contact(ContactRecord(aci = alice)))
        var announced = 0
        storage.onManifest = {
            if (announced < 3) {
                announced++
                sync.onStorageChanged()
                sync.onStorageChanged()
            }
        }

        sync.onStorageChanged()

        // The first read, then one per read that was announced over.
        assertEquals(4, storage.manifestCalls.size)
    }

    /** Archived contact and group records archive their chats; the account record carries "keep muted chats archived". */
    @Test
    fun archivedRecordsArchiveTheirChats() {
        giveKey()
        val masterKey = ByteArray(32) { (it * 5).toByte() }
        val groupJid = SignalGroupV2Manager.jidFromMasterKey(masterKey)
        fx.db.upsertConversation(jid = groupJid, name = "Climbing", isGroup = true)
        val aliceArchived = contact(ContactRecord(aci = alice, archived = true))
        val groupArchived = SignalStorageRecord(
            StorageId.forGroupV2(byteArrayOf((++nextId).toByte())),
            StorageRecord(groupV2 = GroupV2Record(masterKey = masterKey.toByteString(), archived = true)),
        )
        val account = SignalStorageRecord(
            StorageId.forAccount(byteArrayOf((++nextId).toByte())),
            StorageRecord(account = AccountRecord(keepMutedChatsArchived = true)),
        )
        storage.publish(1, aliceArchived, groupArchived, account, contact(ContactRecord(aci = bob)))

        assertEquals(StorageMuteSync.Outcome.UPDATED, sync.read())

        assertEquals(true, fx.db.getConversation(alice)?.isArchived)
        assertEquals(true, fx.db.getConversation(groupJid)?.isArchived)
        assertEquals(false, fx.db.getConversation(bob)?.isArchived)
        assertEquals(true, fx.db.keepMutedChatsArchived())

        // The primary unarchives Alice: her record is replaced by a new one.
        storage.publish(2, contact(ContactRecord(aci = alice)), groupArchived, account)
        sync.read()
        assertEquals(false, fx.db.getConversation(alice)?.isArchived)
    }
}
