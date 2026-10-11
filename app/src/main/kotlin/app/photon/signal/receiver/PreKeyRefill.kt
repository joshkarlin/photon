package app.photon.signal.receiver

import android.util.Log
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.store.PhotonProtocolStore
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.whispersystems.signalservice.api.account.PreKeyUpload
import org.whispersystems.signalservice.api.keys.KeysApi
import org.whispersystems.signalservice.api.push.ServiceIdType
import org.whispersystems.signalservice.api.websocket.SignalWebSocket

private const val TAG = SignalMessageReceiver.TAG

/**
 * Keeps the server stocked with this device's one-time pre-keys, so senders
 * can start sessions with it. Run from the receiver's post-connect upkeep.
 */
internal class PreKeyRefill(private val protocolStore: PhotonProtocolStore) {

    /** Refill when the server's ACI EC or Kyber count is low. Returns whether the check reached the server. */
    fun uploadPreKeysIfNeeded(
        ws: SignalWebSocket.AuthenticatedWebSocket,
        unauthWs: SignalWebSocket.UnauthenticatedWebSocket,
    ): Boolean {
        return try {
            val keysApi = KeysApi(ws, unauthWs)

            val countResult = keysApi.getAvailablePreKeyCounts(ServiceIdType.ACI)
            val counts = countResult.successOrThrow()
            Log.i(
                TAG,
                "Server ACI pre-key counts: ec=${counts.ecCount}, kyber=${counts.kyberCount} " +
                    "(refill threshold=10)",
            )

            if (counts.ecCount >= 10 && counts.kyberCount >= 10) {
                Log.i(TAG, "Server has enough pre-keys")
                return true
            }
            Log.w(
                TAG,
                "Server ACI pre-key counts low; uploading fresh signed, EC, and Kyber keys",
            )

            val identityKeyPair = protocolStore.identityKeyPair

            // (1) Always rotate the signed pre-key on refill. Production clients
            //     do this regularly. Re-uploading the original registration-time
            //     signed pre-key was being rejected with 422 "Invalid signature"
            //     and starving the server of one-time keys, so senders couldn't
            //     start sessions with us — the actual cause of "no incoming msgs".
            val signedPreKeyId = java.util.Random().nextInt(0xFFFFFF)
            val signedKeyPair = ECKeyPair.generate()
            val signedSignature = identityKeyPair.privateKey.calculateSignature(
                signedKeyPair.publicKey.serialize(),
            )
            val signedPreKey = org.signal.libsignal.protocol.state.SignedPreKeyRecord(
                signedPreKeyId, System.currentTimeMillis(), signedKeyPair, signedSignature,
            )
            protocolStore.storeSignedPreKey(signedPreKeyId, signedPreKey)

            // (2) Rotate the last-resort kyber pre-key too.
            val lastResortId = java.util.Random().nextInt(0xFFFFFF)
            val lastResortKp = org.signal.libsignal.protocol.kem.KEMKeyPair.generate(
                org.signal.libsignal.protocol.kem.KEMKeyType.KYBER_1024,
            )
            val lastResortSig = identityKeyPair.privateKey.calculateSignature(
                lastResortKp.publicKey.serialize(),
            )
            val lastResortKyber = org.signal.libsignal.protocol.state.KyberPreKeyRecord(
                lastResortId, System.currentTimeMillis(), lastResortKp, lastResortSig,
            )
            protocolStore.storeLastResortKyberPreKey(lastResortId, lastResortKyber)

            // (3) Fresh batches of one-time EC + Kyber pre-keys.
            val ecPreKeys = mutableListOf<PreKeyRecord>()
            val ecStartId = (counts.ecCount + 1).coerceAtLeast(1)
            for (i in ecStartId until (ecStartId + 100)) {
                val keyPair = ECKeyPair.generate()
                val rec = PreKeyRecord(i, keyPair)
                protocolStore.storePreKey(i, rec)
                ecPreKeys.add(rec)
            }
            val kyberPreKeys = mutableListOf<org.signal.libsignal.protocol.state.KyberPreKeyRecord>()
            val kyberStartId = (counts.kyberCount + 2000).coerceAtLeast(2000)
            for (i in kyberStartId until (kyberStartId + 100)) {
                val kp = org.signal.libsignal.protocol.kem.KEMKeyPair.generate(
                    org.signal.libsignal.protocol.kem.KEMKeyType.KYBER_1024,
                )
                val sig = identityKeyPair.privateKey.calculateSignature(kp.publicKey.serialize())
                val rec = org.signal.libsignal.protocol.state.KyberPreKeyRecord(
                    i, System.currentTimeMillis(), kp, sig,
                )
                protocolStore.storeKyberPreKey(i, rec)
                kyberPreKeys.add(rec)
            }

            // (4) Upload over the authenticated socket. A failure returns
            //     false, so the next connect retries with fresh keys.
            val upload = PreKeyUpload(
                ServiceIdType.ACI, signedPreKey, ecPreKeys, lastResortKyber, kyberPreKeys,
            )
            keysApi.setPreKeys(upload).successOrThrow()
            Log.i(TAG, "Uploaded fresh keys via KeysApi: signed=$signedPreKeyId " +
                "lastResortKyber=$lastResortId ec=${ecPreKeys.size} kyber=${kyberPreKeys.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload pre-keys", e)
            false
        }
    }
}
