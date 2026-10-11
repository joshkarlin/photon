package app.photon.signal

import android.util.Base64
import android.util.Log
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.updateContactProfileName
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.signal.libsignal.zkgroup.ServerPublicParams
import org.signal.libsignal.zkgroup.profiles.ClientZkProfileOperations
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.whispersystems.signalservice.api.crypto.ProfileCipher
import org.whispersystems.signalservice.api.profiles.SignalServiceProfile
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.services.ProfileService
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration

/**
 * Fetches and decrypts Signal profile names using the authenticated websocket.
 *
 * Signal profile names are returned by the server as base64-encoded ciphertext
 * encrypted with the contact's profile key. Profile keys are sent inline on
 * every DataMessage from a contact, so Photon captures them in SignalMessageDatabase
 * and uses them here to resolve a human-readable display name.
 */
class SignalProfileFetcher(
    config: SignalServiceConfiguration,
    private val authWebSocket: SignalWebSocket.AuthenticatedWebSocket,
    private val unauthWebSocket: SignalWebSocket.UnauthenticatedWebSocket,
    private val messageDb: SignalMessageDatabase,
    private val scope: CoroutineScope,
) {
    companion object { private const val TAG = "SignalProfileFetcher" }

    private val profileService: ProfileService = run {
        val serverParams = ServerPublicParams(config.zkGroupServerPublicParams)
        val zkOps = ClientZkProfileOperations(serverParams)
        ProfileService(zkOps, authWebSocket, unauthWebSocket)
    }

    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun fetchAsync(aci: String, profileKeyBytes: ByteArray) {
        if (!inFlight.add(aci)) return
        scope.launch(Dispatchers.IO) {
            val name = try {
                fetchBlocking(aci, profileKeyBytes)
            } catch (e: Throwable) {
                Log.w(TAG, "Profile fetch threw for $aci: ${e.javaClass.name}: ${e.message}", e)
                null
            } finally {
                inFlight.remove(aci)
            }
            try {
                messageDb.updateContactProfileName(aci, name)
            } catch (e: Throwable) {
                Log.w(TAG, "DB write failed for $aci profile: ${e.message}", e)
            }
        }
    }

    private fun fetchBlocking(aci: String, profileKeyBytes: ByteArray): String? {
        // These JIDs are ACIs by construction in our schema (conversation key
        // is sender ACI for DMs). Build the ACI directly from the UUID rather
        // than parseOrNull, which can ambiguously return PNI.
        val coreServiceId = try {
            org.signal.core.models.ServiceId.ACI.from(UUID.fromString(aci))
        } catch (e: Exception) {
            Log.w(TAG, "Bad ACI uuid '$aci': ${e.message}")
            return null
        }
        val address = SignalServiceAddress(coreServiceId)
        val profileKey = ProfileKey(profileKeyBytes)

        val response = try {
            profileService.getProfile(
                address,
                Optional.of(profileKey),
                null,
                SignalServiceProfile.RequestType.PROFILE,
                Locale.getDefault(),
            ).timeout(10, TimeUnit.SECONDS).blockingGet()
        } catch (e: Exception) {
            Log.w(TAG, "getProfile threw for $aci: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }

        val profileAndCredential = response.getResult().orElse(null) ?: return null
        val encryptedName = profileAndCredential.profile.name ?: return null
        val cipher = ProfileCipher(profileKey)
        val decrypted = try {
            cipher.decryptString(Base64.decode(encryptedName, Base64.DEFAULT))
        } catch (e: Exception) {
            Log.w(TAG, "Profile name decrypt failed for $aci: ${e.message}")
            return null
        }
        // Signal separates given/family name with a NUL byte; make it a space.
        // (Escaped explicitly -- this used to be a raw \u0000 in the source,
        // which rendered as an invisible "space" and invited bad edits.)
        val name = decrypted.split('\u0000').filter { it.isNotBlank() }.joinToString(" ").trim()
        Log.i(TAG, "Resolved profile name for $aci: $name")
        return name.ifBlank { null }
    }
}
