package app.photon.signal.store

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.photon.signal.SignalConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.IdentityKeyStore

enum class IdentityVerification { DEFAULT, VERIFIED, UNVERIFIED }

data class ContactIdentity(
    val address: String,
    val key: IdentityKey,
    val verification: IdentityVerification,
    val changedAt: Long,
    val changePending: Boolean,
    val approved: Boolean,
)

class PhotonIdentityKeyStore(
    private val db: SignalProtocolDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : IdentityKeyStore {
    private val sessionLock = SignalConfig.newSessionLock()
    private val sessions = PhotonSessionStore(db)
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes

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
    ): IdentityKeyStore.IdentityChange = sessionLock.acquire().use {
        check(!isSelf(address.name) || identityKey == identityKeyPair.publicKey) {
            "Cannot replace our own Signal identity"
        }
        val existing = getRecord(address.name)
        // Libsignal calls saveIdentity for ordinary encryption/decryption too.
        if (existing?.key == identityKey) return@use IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        val replaced = existing != null
        val verification = if (existing?.verification in
            listOf(IdentityVerification.VERIFIED, IdentityVerification.UNVERIFIED)
        ) IdentityVerification.UNVERIFIED else IdentityVerification.DEFAULT
        val sdb = db.writableDatabase
        sdb.beginTransaction()
        try {
            sdb.insertWithOnConflict("identities", null, ContentValues().apply {
                put("address", address.name)
                put("identity_key", identityKey.serialize())
                put("trust_level", verification.ordinal)
                put("timestamp", now())
                put("change_pending", if (replaced) 1 else 0)
                put("approved", if (replaced) 0 else 1)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            if (replaced) sessions.archiveSessions(address.name, exceptDeviceId = address.deviceId)
            sdb.setTransactionSuccessful()
        } finally {
            sdb.endTransaction()
        }
        _changes.value++
        if (replaced) IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        else IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean = sessionLock.acquire().use {
        if (isSelf(address.name)) return@use identityKey == identityKeyPair.publicKey
        // Accept incoming contact rotations. Libsignal authenticates the message
        // before saving its identity; send approval is a separate decision.
        if (direction == IdentityKeyStore.Direction.RECEIVING) return@use true
        val stored = getRecord(address.name) ?: return@use true
        stored.key == identityKey && stored.verification != IdentityVerification.UNVERIFIED &&
            (stored.approved || now() - stored.changedAt >= 5_000L)
    }

    private fun isSelf(name: String): Boolean = listOf("aci", "pni", "phone_number")
        .any { db.getState(it)?.let { value -> String(value) } == name }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = getRecord(address.name)?.key

    fun getRecord(name: String): ContactIdentity? = db.readableDatabase.rawQuery(
        "SELECT identity_key, trust_level, timestamp, change_pending, approved FROM identities WHERE address = ?",
        arrayOf(name),
    ).use { c ->
        if (!c.moveToFirst()) null else ContactIdentity(
            name, IdentityKey(c.getBlob(0)), IdentityVerification.entries[c.getInt(1)],
            c.getLong(2), c.getInt(3) != 0, c.getInt(4) != 0,
        )
    }

    // A key learned from a failed outbound pre-key fetch must invalidate the
    // current device's old session too. During receive, libsignal replaces it.
    fun recordSendIdentityFailure(name: String, key: IdentityKey) = sessionLock.acquire().use {
        val address = SignalProtocolAddress(name, 1)
        if (saveIdentity(address, key) == IdentityKeyStore.IdentityChange.REPLACED_EXISTING) {
            sessions.archiveSession(address)
        }
    }

    fun acceptChange(expected: ContactIdentity): Boolean = sessionLock.acquire().use {
        // Bind the user's approval to the exact state they reviewed.
        if (getRecord(expected.address) != expected || !expected.changePending) return@use false
        updateState(expected.address, IdentityVerification.DEFAULT, approved = true, pending = false)
        true
    }

    fun applyVerificationSync(name: String, key: IdentityKey, state: IdentityVerification) =
        sessionLock.acquire().use {
            if (isSelf(name)) return@use
            val existing = getRecord(name)
            when (state) {
                IdentityVerification.VERIFIED -> {
                    recordSendIdentityFailure(name, key)
                    updateState(name, state, approved = true, pending = false)
                }
                // A DEFAULT sync clears verification for the same key only.
                IdentityVerification.DEFAULT -> if (existing?.key == key) {
                    updateState(name, state, approved = true, pending = existing.changePending)
                }
                IdentityVerification.UNVERIFIED -> Unit
            }
        }

    private fun updateState(name: String, state: IdentityVerification, approved: Boolean, pending: Boolean) {
        db.writableDatabase.update("identities", ContentValues().apply {
            put("trust_level", state.ordinal)
            put("approved", if (approved) 1 else 0)
            put("change_pending", if (pending) 1 else 0)
        }, "address = ?", arrayOf(name))
        _changes.value++
    }
}
