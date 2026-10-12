package app.photon.signal.storage

import app.photon.signal.db.StorageMute
import okio.ByteString
import org.signal.core.models.AccountEntropyPool
import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.api.storage.SignalStorageRecord
import org.whispersystems.signalservice.api.storage.StorageId
import org.whispersystems.signalservice.internal.storage.protos.ContactRecord
import org.whispersystems.signalservice.internal.storage.protos.ManifestRecord
import java.util.Base64

// What Photon takes from the storage service: each chat's mute and archive
// state, and the account's "keep muted chats archived". Pure mapping, so it
// can be tested without a network or the library's crypto.

/**
 * The storage service key for [accountEntropyPool] (from the provisioning
 * message or a Keys sync), or null when there's no valid pool. It decrypts
 * the manifest and every record; it's a secret, so it's never logged.
 */
internal fun storageKeyFrom(accountEntropyPool: String?): ByteArray? {
    val pool = accountEntropyPool?.takeIf { it.isNotBlank() }?.let { AccountEntropyPool.parseOrNull(it) } ?: return null
    return try {
        pool.deriveMasterKey().deriveStorageServiceKey().serialize()
    } catch (_: Exception) {
        null
    }
}

/**
 * The record types that carry a chat's state: contacts (DMs), GroupV2
 * groups, and the account (Note to Self's archive, and "keep muted chats
 * archived").
 */
internal fun carriesChatState(id: StorageId): Boolean =
    id.type == ManifestRecord.Identifier.Type.CONTACT.value ||
        id.type == ManifestRecord.Identifier.Type.GROUPV2.value ||
        id.type == ManifestRecord.Identifier.Type.ACCOUNT.value

/** A storage id as storage_records keys it. */
internal fun storageIdKey(id: StorageId): String = Base64.getEncoder().encodeToString(id.raw)

/**
 * A record's mutedUntilTimestamp (epoch ms; 0 = not muted; Long.MAX_VALUE =
 * forever) as muted_until (0 = not muted, -1 = forever, else epoch seconds).
 * The field is a uint64, so anything past Long.MAX_VALUE reads negative:
 * that's forever too.
 */
internal fun muteEndSeconds(mutedUntilMs: Long): Long = when {
    mutedUntilMs == 0L -> 0
    mutedUntilMs < 0 || mutedUntilMs == Long.MAX_VALUE -> -1
    else -> mutedUntilMs / 1000
}

/**
 * The conversation a contact record is about, keyed as the receiver keys
 * DMs: their ACI. A record without one (known only by phone number or PNI)
 * maps through the phone to an ACI Photon already knows, as a PNI merge
 * does ([aciForPhone]; [phoneOf] gives a stored id's phone), and otherwise
 * to the PNI, or the number, a conversation with them would be keyed by
 * until their ACI is known.
 */
internal fun contactConversationJid(
    contact: ContactRecord,
    phoneOf: (String) -> String?,
    aciForPhone: (String) -> String?,
): String? {
    serviceId(contact.aci, contact.aciBinary)?.takeIf { it is ServiceId.ACI }?.let { return it.toString() }
    val pni = serviceId(contact.pni, contact.pniBinary, pni = true)?.takeIf { it is ServiceId.PNI }?.toString()
    val e164 = contact.e164.takeIf { it.isNotBlank() }
    val phone = e164 ?: pni?.let(phoneOf)
    phone?.let(aciForPhone)?.let { return it }
    return pni ?: e164
}

/** A service id from a record's binary field, or else its string field; null when neither is a known id. */
private fun serviceId(string: String, binary: ByteString, pni: Boolean = false): ServiceId? {
    val parsed = if (binary.size > 0) {
        if (pni) ServiceId.PNI.parseOrNull(binary.toByteArray()) else ServiceId.ACI.parseOrNull(binary.toByteArray())
    } else {
        string.takeIf { it.isNotBlank() }?.let { if (pni) ServiceId.PNI.parseOrNull(it) else ServiceId.ACI.parseOrNull(it) }
    }
    return parsed?.takeUnless { it.isUnknown }
}

/**
 * The mute and archive state [record] gives a chat. [contactJid] maps a
 * contact record to its conversation ([contactConversationJid]); [groupJid]
 * a GroupV2 master key to the group's jid. The account record is Note to
 * Self's ([selfJid], the account's ACI): it's never muted, and it carries
 * "keep muted chats archived". A record that maps to no chat keeps a null
 * jid.
 */
internal fun storageMuteOf(
    record: SignalStorageRecord,
    contactJid: (ContactRecord) -> String?,
    groupJid: (ByteArray) -> String?,
    selfJid: String?,
): StorageMute {
    val id = storageIdKey(record.id)
    val contact = record.proto.contact
    val group = record.proto.groupV2
    val account = record.proto.account
    return when {
        contact != null ->
            StorageMute(id, contactJid(contact), muteEndSeconds(contact.mutedUntilTimestamp), archived = contact.archived)
        group != null -> StorageMute(
            id, group.masterKey.toByteArray().takeIf { it.size == 32 }?.let(groupJid),
            muteEndSeconds(group.mutedUntilTimestamp), archived = group.archived,
        )
        account != null -> StorageMute(
            id, selfJid, 0, archived = account.noteToSelfArchived,
            keepMutedChatsArchived = account.keepMutedChatsArchived,
        )
        else -> StorageMute(id, null, 0)
    }
}
