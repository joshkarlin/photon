package app.photon.signal

import app.photon.signal.store.IdentityVerification
import app.photon.signal.store.PhotonIdentityKeyStore
import org.signal.core.models.ServiceId
import org.signal.libsignal.protocol.IdentityKey
import org.whispersystems.signalservice.internal.push.Verified

internal fun applySignalVerificationSync(
    store: PhotonIdentityKeyStore,
    verified: Verified,
    sourceAci: String,
    localAci: String?,
): Boolean {
    if (localAci == null || sourceAci != localAci) return false
    val binaryDestination = verified.destinationAciBinary
    val destination = if (binaryDestination != null) {
        ServiceId.parseOrNull(binaryDestination.toByteArray()) as? ServiceId.ACI
    } else verified.destinationAci?.let { ServiceId.ACI.parseOrNull(it) }
    if (destination == null || destination.toString() == localAci) return false
    val keyBytes = verified.identityKey?.toByteArray() ?: return false
    val key = try { IdentityKey(keyBytes) } catch (_: Exception) { return false }
    val state = when (verified.state) {
        Verified.State.DEFAULT -> IdentityVerification.DEFAULT
        Verified.State.VERIFIED -> IdentityVerification.VERIFIED
        // Signal/Molly do not apply UNVERIFIED syncs as an approval or replacement.
        else -> return false
    }
    store.applyVerificationSync(destination.toString(), key, state)
    return true
}
