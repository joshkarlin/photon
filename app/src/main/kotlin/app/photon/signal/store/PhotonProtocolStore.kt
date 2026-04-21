package app.photon.signal.store

import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import org.whispersystems.signalservice.api.SignalServiceAccountDataStore
import org.whispersystems.signalservice.api.SignalServiceKyberPreKeyStore
import org.whispersystems.signalservice.api.SignalServicePreKeyStore
import org.whispersystems.signalservice.api.SignalServiceSenderKeyStore
import org.whispersystems.signalservice.api.SignalServiceSessionStore
import org.whispersystems.signalservice.api.push.DistributionId

class PhotonProtocolStore(db: SignalProtocolDatabase) :
    SignalServiceAccountDataStore,
    IdentityKeyStore by PhotonIdentityKeyStore(db),
    SessionStore by PhotonSessionStore(db),
    PreKeyStore by PhotonPreKeyStore(db),
    SignedPreKeyStore by PhotonSignedPreKeyStore(db),
    KyberPreKeyStore by PhotonKyberPreKeyStore(db),
    SenderKeyStore by PhotonSenderKeyStore(db) {

    private val kyberStore = PhotonKyberPreKeyStore(db)

    // SignalServiceAccountDataStore
    override fun isMultiDevice(): Boolean = true

    // SignalServicePreKeyStore extras
    override fun markAllOneTimeEcPreKeysStaleIfNecessary(staleTime: Long) {}
    override fun deleteAllStaleOneTimeEcPreKeys(threshold: Long, minCount: Int) {}

    // SignalServiceSessionStore extras
    override fun archiveSession(address: SignalProtocolAddress) {
        // Archive = delete for our simple implementation
        deleteSession(address)
    }

    override fun getAllAddressesWithActiveSessions(addressNames: MutableList<String>): MutableMap<SignalProtocolAddress, SessionRecord> {
        return mutableMapOf()
    }

    // SignalServiceSenderKeyStore extras
    override fun getSenderKeySharedWith(distributionId: DistributionId): MutableSet<SignalProtocolAddress> {
        return mutableSetOf()
    }

    override fun markSenderKeySharedWith(
        distributionId: DistributionId,
        addresses: MutableCollection<SignalProtocolAddress>,
    ) {}

    override fun clearSenderKeySharedWith(addresses: MutableCollection<SignalProtocolAddress>) {}

    // SignalServiceKyberPreKeyStore extras
    override fun storeLastResortKyberPreKey(id: Int, record: KyberPreKeyRecord) {
        kyberStore.storeKyberPreKey(id, record, isLastResort = true)
    }

    override fun loadLastResortKyberPreKeys(): MutableList<KyberPreKeyRecord> {
        return loadKyberPreKeys()
    }

    override fun removeKyberPreKey(id: Int) {
        // No-op for last resort keys
    }

    override fun markAllOneTimeKyberPreKeysStaleIfNecessary(staleTime: Long) {}
    override fun deleteAllStaleOneTimeKyberPreKeys(threshold: Long, minCount: Int) {}
}
