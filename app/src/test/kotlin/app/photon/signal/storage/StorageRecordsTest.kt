package app.photon.signal.storage

import app.photon.signal.db.StorageMute
import okio.ByteString.Companion.toByteString
import org.junit.Test
import org.signal.core.models.AccountEntropyPool
import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.api.storage.SignalStorageRecord
import org.whispersystems.signalservice.api.storage.StorageId
import org.whispersystems.signalservice.internal.storage.protos.AccountRecord
import org.whispersystems.signalservice.internal.storage.protos.ContactRecord
import org.whispersystems.signalservice.internal.storage.protos.GroupV2Record
import org.whispersystems.signalservice.internal.storage.protos.StorageRecord
import java.util.Base64
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Storage records → which chat they're about, its mute and whether it's archived. */
class StorageRecordsTest {

    private val aci = "00000000-0000-4000-8000-00000000a11c"
    private val aciUuid = UUID.fromString(aci)
    private val pniUuid = "00000000-0000-4000-8000-0000000000b1"
    private val pni = "PNI:$pniUuid"
    private val phone = "+15550001111"

    private val noPhones: (String) -> String? = { null }

    private fun uuidBytes(uuid: UUID) =
        java.nio.ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

    private fun jid(contact: ContactRecord, phoneOf: (String) -> String? = noPhones, aciForPhone: (String) -> String? = noPhones) =
        contactConversationJid(contact, phoneOf, aciForPhone)

    @Test
    fun muteEndsBecomeSeconds() {
        assertEquals(0, muteEndSeconds(0))
        assertEquals(-1, muteEndSeconds(Long.MAX_VALUE))
        // A uint64 past Long.MAX_VALUE.
        assertEquals(-1, muteEndSeconds(-1))
        assertEquals(1_700_003_600, muteEndSeconds(1_700_003_600_999))
    }

    @Test
    fun aContactWithAnAciIsTheirDm() {
        assertEquals(aci, jid(ContactRecord(aci = aci, pni = pniUuid, e164 = phone)))
        assertEquals(aci, jid(ContactRecord(aciBinary = uuidBytes(aciUuid).toByteString())))
    }

    /** As the PNI merge does: a number Photon already knows the ACI of is that ACI's chat. */
    @Test
    fun aContactKnownByNumberMapsToTheAciForIt() {
        val acis = mapOf(phone to aci)
        assertEquals(aci, jid(ContactRecord(pni = pniUuid, e164 = phone), aciForPhone = acis::get))
        // No number on the record: the one stored for the PNI.
        assertEquals(
            aci,
            jid(ContactRecord(pni = pniUuid), phoneOf = mapOf(pni to phone)::get, aciForPhone = acis::get),
        )
    }

    /** Until the ACI is known, the record maps to the PNI (or number) a chat with them is keyed by. */
    @Test
    fun aContactWithoutAKnownAciMapsToItsPniOrNumber() {
        assertEquals(pni, jid(ContactRecord(pni = pniUuid, e164 = phone)))
        assertEquals(pni, jid(ContactRecord(pni = pni)))
        assertEquals(pni, jid(ContactRecord(pniBinary = uuidBytes(UUID.fromString(pniUuid)).toByteString())))
        assertEquals(phone, jid(ContactRecord(e164 = phone)))
        assertNull(jid(ContactRecord()))
        // A zero id is no id.
        assertNull(jid(ContactRecord(aci = "00000000-0000-0000-0000-000000000000")))
    }

    @Test
    fun contactAndGroupRecordsCarryTheirMute() {
        val contactId = StorageId.forContact(byteArrayOf(1, 2, 3))
        val groupId = StorageId.forGroupV2(byteArrayOf(4, 5, 6))
        val masterKey = ByteArray(32) { 9 }
        val groupJids = { mk: ByteArray -> if (mk.contentEquals(masterKey)) "group-jid" else null }

        val contact = SignalStorageRecord(
            contactId, StorageRecord(contact = ContactRecord(aci = aci, mutedUntilTimestamp = Long.MAX_VALUE)),
        )
        val group = SignalStorageRecord(
            groupId,
            StorageRecord(groupV2 = GroupV2Record(masterKey = masterKey.toByteString(), mutedUntilTimestamp = 1_700_000_000_000)),
        )

        assertEquals(
            StorageMute(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), aci, -1),
            storageMuteOf(contact, { jid(it) }, groupJids, selfJid = null),
        )
        assertEquals(
            StorageMute(Base64.getEncoder().encodeToString(byteArrayOf(4, 5, 6)), "group-jid", 1_700_000_000),
            storageMuteOf(group, { jid(it) }, groupJids, selfJid = null),
        )
    }

    @Test
    fun contactAndGroupRecordsCarryTheirArchive() {
        val masterKey = ByteArray(32) { 9 }
        val contact = SignalStorageRecord(
            StorageId.forContact(byteArrayOf(1)), StorageRecord(contact = ContactRecord(aci = aci, archived = true)),
        )
        val group = SignalStorageRecord(
            StorageId.forGroupV2(byteArrayOf(2)),
            StorageRecord(groupV2 = GroupV2Record(masterKey = masterKey.toByteString(), archived = true)),
        )
        val unarchived = SignalStorageRecord(
            StorageId.forContact(byteArrayOf(3)), StorageRecord(contact = ContactRecord(aci = aci)),
        )

        assertTrue(storageMuteOf(contact, { jid(it) }, { "group-jid" }, selfJid = null).archived)
        assertEquals("group-jid", storageMuteOf(group, { jid(it) }, { "group-jid" }, selfJid = null).jid)
        assertTrue(storageMuteOf(group, { jid(it) }, { "group-jid" }, selfJid = null).archived)
        assertFalse(storageMuteOf(unarchived, { jid(it) }, { "group-jid" }, selfJid = null).archived)
    }

    /**
     * The account record is Note to Self's (its archive) and carries "keep
     * muted chats archived"; it's never muted.
     */
    @Test
    fun theAccountRecordIsNoteToSelfAndKeepsMutedChatsArchived() {
        val account = SignalStorageRecord(
            StorageId.forAccount(byteArrayOf(8)),
            StorageRecord(account = AccountRecord(noteToSelfArchived = true, keepMutedChatsArchived = true)),
        )

        assertEquals(
            StorageMute("CA==", "me", 0, archived = true, keepMutedChatsArchived = true),
            storageMuteOf(account, { jid(it) }, { "never" }, selfJid = "me"),
        )
    }

    @Test
    fun recordsThatNameNoChatMapToNone() {
        val badGroup = SignalStorageRecord(
            StorageId.forGroupV2(byteArrayOf(7)),
            StorageRecord(groupV2 = GroupV2Record(masterKey = ByteArray(5).toByteString(), mutedUntilTimestamp = 1)),
        )
        // Before the account is known (no ACI), its record names no chat.
        val account = SignalStorageRecord(StorageId.forAccount(byteArrayOf(8)), StorageRecord(account = AccountRecord()))

        assertNull(storageMuteOf(badGroup, { jid(it) }, { "never" }, selfJid = null).jid)
        assertEquals(StorageMute("CA==", null, 0), storageMuteOf(account, { jid(it) }, { "never" }, selfJid = null))
    }

    @Test
    fun onlyContactGroupV2AndAccountRecordsAreRead() {
        assertTrue(carriesChatState(StorageId.forContact(byteArrayOf(1))))
        assertTrue(carriesChatState(StorageId.forGroupV2(byteArrayOf(1))))
        assertTrue(carriesChatState(StorageId.forAccount(byteArrayOf(1))))
        assertFalse(carriesChatState(StorageId.forGroupV1(byteArrayOf(1))))
        assertFalse(carriesChatState(StorageId.forStoryDistributionList(byteArrayOf(1))))
    }

    /** The storage key is the AEP's master key's storage-service key; a malformed pool gives none. */
    @Test
    fun theStorageKeyComesFromTheAccountEntropyPool() {
        val pool = AccountEntropyPool.generate()

        val key = assertNotNull(storageKeyFrom(pool.value))

        assertEquals(32, key.size)
        assertContentEquals(pool.deriveMasterKey().deriveStorageServiceKey().serialize(), key)
        assertNull(storageKeyFrom(null))
        assertNull(storageKeyFrom(""))
        assertNull(storageKeyFrom("not a pool"))
    }

    @Test
    fun aServiceIdParsesAsExpected() {
        // Guards the library's parsing the mapping relies on.
        assertEquals(aci, ServiceId.ACI.parseOrNull(aci).toString())
        assertNull(ServiceId.ACI.parseOrNull(pni))
    }
}
