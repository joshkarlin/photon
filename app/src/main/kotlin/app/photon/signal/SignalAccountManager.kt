package app.photon.signal

import android.util.Log
import app.photon.signal.store.SignalProtocolDatabase
import app.photon.signal.store.PhotonProtocolStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import org.signal.libsignal.protocol.util.Medium
import org.whispersystems.signalservice.api.account.AccountAttributes
import org.whispersystems.signalservice.api.account.PreKeyCollection
import org.whispersystems.signalservice.api.provisioning.ProvisioningSocket
import org.whispersystems.signalservice.api.util.DeviceNameUtil
import org.whispersystems.signalservice.internal.push.ProvisionMessage
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import java.io.Closeable
import java.security.SecureRandom

class SignalAccountManager(
    private val protocolStore: PhotonProtocolStore,
    private val credentials: SignalCredentials,
    private val protocolDb: SignalProtocolDatabase,
) {
    companion object {
        private const val TAG = "SignalAccountManager"
        private const val EC_PREKEY_BATCH_SIZE = 100
        private const val EC_PREKEY_START_ID = 1
    }

    private val config = SignalConfig.createConfiguration()

    private val _signalState = MutableStateFlow(
        if (credentials.isRegistered()) "disconnected" else "logged_out"
    )
    val signalState: StateFlow<String> = _signalState

    private var provisioningCloseable: Closeable? = null

    suspend fun startProvisioning(
        onQrUrl: (String) -> Unit,
        onComplete: (Boolean, String?) -> Unit,
    ) {
        _signalState.value = "connecting"
        try {
            val identityKeyPair = IdentityKeyPair.generate()

            provisioningCloseable = ProvisioningSocket.Companion.start<ProvisionMessage>(
                ProvisioningSocket.Mode.LINK,
                identityKeyPair,
                config,
                object : ProvisioningSocket.ProvisioningSocketExceptionHandler {
                    override fun handleException(socketId: Int, throwable: Throwable) {
                        Log.e(TAG, "Provisioning socket $socketId error", throwable)
                        _signalState.value = "disconnected"
                        onComplete(false, throwable.message)
                    }
                },
            ) { socket ->
                try {
                    val url = socket.getProvisioningUrl()
                    Log.i(TAG, "Provisioning URL received")
                    onQrUrl(url)

                    val result = socket.getProvisioningMessageDecryptResult()
                    Log.i(TAG, "Provisioning message received, type: ${result?.javaClass?.name}")

                    finishProvisioning(result, identityKeyPair)
                    onComplete(true, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Provisioning failed", e)
                    _signalState.value = "disconnected"
                    onComplete(false, e.message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start provisioning", e)
            _signalState.value = "disconnected"
            onComplete(false, e.message)
        }
    }

    private fun finishProvisioning(
        result: Any?,
        localIdentityKeyPair: IdentityKeyPair,
    ) {
        try {
            val provisionMsg = extractProvisionMessage(result)
                ?: throw IllegalStateException("Could not extract provision message from result: ${result?.javaClass?.name}")

            Log.i(TAG, "Provision message: number=${provisionMsg.number}, aci=${provisionMsg.aci}, " +
                "hasAciPub=${provisionMsg.aciIdentityKeyPublic != null}, " +
                "hasAciPriv=${provisionMsg.aciIdentityKeyPrivate != null}")

            val phoneNumber = provisionMsg.number
                ?: throw IllegalStateException("No phone number in provision message")
            val aci = provisionMsg.aci
                ?: throw IllegalStateException("No ACI in provision message")
            val pni = provisionMsg.pni
            val provisioningCode = provisionMsg.provisioningCode
                ?: throw IllegalStateException("No provisioning code")

            // Reconstruct ACI identity key pair from provision message.
            // The provision message contains raw EC key bytes (32 bytes private, 33 bytes public with type prefix).
            val aciIdentityKeyPair = reconstructIdentityKeyPair(
                provisionMsg.aciIdentityKeyPublic?.toByteArray(),
                provisionMsg.aciIdentityKeyPrivate?.toByteArray(),
                localIdentityKeyPair,
                "ACI",
            )

            val pniIdentityKeyPair = reconstructIdentityKeyPair(
                provisionMsg.pniIdentityKeyPublic?.toByteArray(),
                provisionMsg.pniIdentityKeyPrivate?.toByteArray(),
                IdentityKeyPair.generate(),
                "PNI",
            )

            val registrationId = KeyHelper.generateRegistrationId(false)
            val pniRegistrationId = KeyHelper.generateRegistrationId(false)

            // Store identity key pair and registration ID
            protocolDb.putState("identity_key_pair", aciIdentityKeyPair.serialize())
            protocolDb.putStateInt("registration_id", registrationId)

            val password = generatePassword()

            credentials.phoneNumber = phoneNumber
            credentials.aciString = aci
            credentials.pniString = pni
            credentials.storedPassword = password

            val pushSocket = PushServiceSocket(
                config, credentials, SignalConfig.USER_AGENT, false,
            )

            // Generate pre-key collections for registration
            val aciPreKeyCollection = generatePreKeyCollection(aciIdentityKeyPair)
            val pniPreKeyCollection = generatePreKeyCollection(pniIdentityKeyPair)

            // Signal's primary device expects the device name field to be a base64
            // DeviceName proto (ephemeral pubkey + synthetic IV + AES-encrypted name),
            // not plaintext. Plaintext shows up on the primary's linked-devices list
            // as truncated garbage like "Photog==" because the UI base64-decodes it.
            val encryptedDeviceName = DeviceNameUtil.encryptDeviceName(
                "Photon", aciIdentityKeyPair.privateKey,
            )

            val deviceId = pushSocket.finishNewDeviceRegistration(
                provisioningCode,
                AccountAttributes(
                    null,       // signalingKey (deprecated)
                    registrationId,
                    true,       // fetchesMessages
                    null,       // registrationLock
                    null,       // unidentifiedAccessKey
                    false,      // unrestrictedUnidentifiedAccess
                    AccountAttributes.Capabilities(
                        storage = true,
                        versionedExpirationTimer = true,
                        attachmentBackfill = true,
                        spqr = true,
                    ),
                    true,              // discoverableByPhoneNumber
                    encryptedDeviceName,
                    pniRegistrationId,
                    null,              // recoveryPassword
                ),
                aciPreKeyCollection,
                pniPreKeyCollection,
            )

            credentials.storedDeviceId = deviceId

            // Generate one-time EC pre-keys and store locally.
            // They'll be uploaded when the message receiver connects via KeysApi.
            generateAndStoreOneTimePreKeys()

            _signalState.value = "connected"
            Log.i(TAG, "Device linked: $phoneNumber, device $deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finish provisioning", e)
            credentials.clear() // Clear partial credentials so we don't appear "registered"
            _signalState.value = "disconnected"
            throw e
        }
    }

    private fun reconstructIdentityKeyPair(
        pubBytes: ByteArray?,
        privBytes: ByteArray?,
        fallback: IdentityKeyPair,
        label: String,
    ): IdentityKeyPair {
        if (pubBytes == null || privBytes == null) {
            Log.w(TAG, "$label identity key bytes missing, using fallback")
            return fallback
        }
        return try {
            // Try parsing as-is (public key may already have type prefix)
            val publicKey = IdentityKey(pubBytes)
            val privateKey = ECPrivateKey(privBytes)
            IdentityKeyPair(publicKey, privateKey)
        } catch (e1: Exception) {
            try {
                // Try treating public key as raw 32 bytes (add Curve25519 type prefix)
                val prefixed = ByteArray(pubBytes.size + 1)
                prefixed[0] = 0x05 // DJB type
                System.arraycopy(pubBytes, 0, prefixed, 1, pubBytes.size)
                val publicKey = IdentityKey(prefixed)
                val privateKey = ECPrivateKey(privBytes)
                IdentityKeyPair(publicKey, privateKey)
            } catch (e2: Exception) {
                Log.w(TAG, "$label identity key parse failed, using fallback", e2)
                fallback
            }
        }
    }

    private fun extractProvisionMessage(result: Any?): ProvisionMessage? {
        if (result == null) return null

        // The result is SecondaryProvisioningCipher.ProvisioningDecryptResult<ProvisionMessage>
        // which is a sealed interface with Success<T>(getMessage()) and Error subclasses
        return try {
            // Use reflection since the Kotlin compiler can't resolve getMessage()
            // on the sealed interface directly. The runtime type is Success<ProvisionMessage>.
            val method = result.javaClass.getMethod("getMessage")
            method.invoke(result) as? ProvisionMessage
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract provision message from ${result.javaClass.name}", e)
            null
        }
    }

    private fun generatePreKeyCollection(identityKeyPair: IdentityKeyPair): PreKeyCollection {
        val random = SecureRandom()

        // Signed EC pre-key
        val signedPreKeyPair = ECKeyPair.generate()
        val signedPreKeyId = random.nextInt(Medium.MAX_VALUE)
        val signedPreKeySignature = identityKeyPair.privateKey.calculateSignature(
            signedPreKeyPair.publicKey.serialize()
        )
        val signedPreKey = SignedPreKeyRecord(
            signedPreKeyId, System.currentTimeMillis(),
            signedPreKeyPair, signedPreKeySignature,
        )

        // Last resort Kyber pre-key
        val kyberPreKeyId = random.nextInt(Medium.MAX_VALUE)
        val kyberKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberSignature = identityKeyPair.privateKey.calculateSignature(
            kyberKeyPair.publicKey.serialize()
        )
        val kyberPreKey = KyberPreKeyRecord(
            kyberPreKeyId, System.currentTimeMillis(),
            kyberKeyPair, kyberSignature,
        )

        // Store locally — Kyber keys in PreKeyCollection are last-resort (must not be deleted after use)
        protocolStore.storeSignedPreKey(signedPreKeyId, signedPreKey)
        protocolStore.storeLastResortKyberPreKey(kyberPreKeyId, kyberPreKey)

        Log.i(TAG, "Generated pre-key collection: signedPreKey=$signedPreKeyId, kyberPreKey=$kyberPreKeyId")
        // Verify roundtrip
        val storedKyber = protocolStore.loadKyberPreKey(kyberPreKeyId)
        Log.i(TAG, "Verified stored Kyber key: id=${storedKyber.id}")

        return PreKeyCollection(identityKeyPair.publicKey, signedPreKey, kyberPreKey)
    }

    /**
     * Generate one-time EC pre-keys and store locally.
     * These are needed when other contacts initiate sessions with us.
     * Upload happens via KeysApi when the message receiver connects.
     */
    private fun generateAndStoreOneTimePreKeys() {
        for (i in EC_PREKEY_START_ID..(EC_PREKEY_START_ID + EC_PREKEY_BATCH_SIZE - 1)) {
            val keyPair = ECKeyPair.generate()
            val preKey = PreKeyRecord(i, keyPair)
            protocolStore.storePreKey(i, preKey)
        }
        Log.i(TAG, "Generated $EC_PREKEY_BATCH_SIZE one-time EC pre-keys")
    }

    private fun generatePassword(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(
            bytes, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
    }

    fun cancelProvisioning() {
        provisioningCloseable?.close()
        provisioningCloseable = null
    }

    fun logout() {
        // Best-effort: ask the Signal server to delete this linked device so the
        // primary stops sending it storage-manifest sync messages. Without this,
        // our deviceId stays registered and primary keeps queueing sync for a
        // device that will never drain it — which is exactly the "sync loop"
        // we've been seeing in logs.
        try {
            val deviceId = credentials.deviceId
            if (credentials.isRegistered() && deviceId > 1) {
                val pushSocket = PushServiceSocket(config, credentials, SignalConfig.USER_AGENT, false)
                val method = pushSocket.javaClass.getDeclaredMethod(
                    "makeServiceRequest", String::class.java, String::class.java, String::class.java,
                )
                method.isAccessible = true
                method.invoke(pushSocket, "/v1/devices/$deviceId", "DELETE", "")
                Log.i(TAG, "Removed linked device $deviceId from Signal server")
            }
        } catch (e: Exception) {
            // The device may already be unlinked (primary unlinked us), credentials
            // may be stale, or the network may be down. Fall through to local clear
            // either way — the user's next action is to re-link anyway.
            Log.w(TAG, "Server-side device removal failed (continuing with local clear): ${e.message}")
        }
        credentials.clear()
        _signalState.value = "logged_out"
    }
}
