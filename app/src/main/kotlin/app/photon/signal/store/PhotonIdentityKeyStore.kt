package app.photon.signal.store

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.IdentityKeyStore

class PhotonIdentityKeyStore(private val db: SignalProtocolDatabase) : IdentityKeyStore {

    override fun getIdentityKeyPair(): IdentityKeyPair {
        val bytes = db.getState("identity_key_pair")
            ?: throw IllegalStateException("No identity key pair stored")
        return IdentityKeyPair(bytes)
    }

    override fun getLocalRegistrationId(): Int {
        return db.getStateInt("registration_id")
            ?: throw IllegalStateException("No registration ID stored")
    }

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
    ): IdentityKeyStore.IdentityChange {
        val existing = getIdentity(address)
        val sdb = db.writableDatabase
        sdb.insertWithOnConflict(
            "identities",
            null,
            ContentValues().apply {
                put("address", address.name)
                put("identity_key", identityKey.serialize())
                put("trust_level", 0)
                put("timestamp", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return if (existing != null && existing != identityKey) {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val stored = getIdentity(address) ?: return true // trust on first use
        return stored == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        return db.readableDatabase.rawQuery(
            "SELECT identity_key FROM identities WHERE address = ?",
            arrayOf(address.name),
        ).use { c ->
            if (c.moveToFirst()) IdentityKey(c.getBlob(0)) else null
        }
    }
}
