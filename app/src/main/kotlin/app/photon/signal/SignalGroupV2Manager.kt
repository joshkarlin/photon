package app.photon.signal

import android.util.Base64
import android.util.Log
import org.signal.core.models.ServiceId
import org.signal.libsignal.zkgroup.auth.AuthCredentialWithPniResponse
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroup
import org.whispersystems.signalservice.api.groupsv2.ClientZkOperations
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Api
import org.whispersystems.signalservice.api.groupsv2.GroupsV2AuthorizationString
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * GroupV2 helper that wraps libsignal-service's GroupsV2Api with the
 * minimum infrastructure Photon needs: credential rotation, group-state
 * fetch with title decryption, and member-list resolution.
 *
 * Photon uses GroupV2 for two things:
 *   1. Receive — when a DataMessage arrives with `groupV2.masterKey`, we
 *      derive a stable group identifier from the master key, store the
 *      master key + revision on the conversation row, then asynchronously
 *      fetch the group state from the server to get the decrypted title +
 *      member ACIs. The title becomes the conversation name; the members
 *      go into the participants table so per-message sender names render.
 *   2. Send — when sending into a group jid, we look up the cached master
 *      key + revision + members and fan out via per-recipient legacy send
 *      with `dataMessage.asGroupMessage(groupContextV2)`. No sender-key
 *      distribution path (more complex, deferred).
 *
 * The credentials API returns a 7-day window of `AuthCredentialWithPniResponse`
 * keyed by day-truncated-seconds. We cache the most recent window and
 * refetch when today's slot is missing.
 */
class SignalGroupV2Manager(
    private val config: SignalServiceConfiguration,
    private val credentials: SignalCredentials,
    private val authWs: SignalWebSocket.AuthenticatedWebSocket,
    private val pushSocket: PushServiceSocket,
) {
    companion object {
        private const val TAG = "SignalGroupV2Manager"
        private const val DAY_SECONDS = 86_400L
    }

    private val clientZk: ClientZkOperations = ClientZkOperations.create(config)
    private val operations: GroupsV2Operations = GroupsV2Operations(clientZk, GroupsV2Operations.HIGHEST_KNOWN_EPOCH)
    private val groupsApi: GroupsV2Api = GroupsV2Api(authWs, pushSocket, operations)

    // Day-truncated-second → credential. Refilled by getOrFetchCredentials().
    private val credentialsByDay = ConcurrentHashMap<Long, AuthCredentialWithPniResponse>()

    /**
     * Derive the stable, encrypted-then-hashed group identifier from a
     * master key. This is the same identifier that libsignal's
     * SignalServiceCipher reports as `metadata.groupId` on incoming
     * envelopes, so we can build a single conversation_jid from either
     * source without one routing into a different thread than the other.
     */
    fun groupIdFromMasterKey(masterKey: ByteArray): ByteArray {
        val mk = GroupMasterKey(masterKey)
        return GroupSecretParams.deriveFromMasterKey(mk)
            .publicParams
            .groupIdentifier
            .serialize()
    }

    /** Base64-URL-safe encoding of the group id — matches receiver's existing jid format. */
    fun jidFromMasterKey(masterKey: ByteArray): String =
        Base64.encodeToString(groupIdFromMasterKey(masterKey), Base64.NO_WRAP or Base64.URL_SAFE)

    /**
     * Fetch and decrypt the current group state. Returns null on any
     * failure (network, auth, decryption, invalid input). Callers should
     * treat the absence of a result as "try again on the next message" —
     * it's not fatal to a message-receive flow.
     */
    fun fetchGroup(masterKey: ByteArray): DecryptedGroup? {
        return try {
            val mk = GroupMasterKey(masterKey)
            val params = GroupSecretParams.deriveFromMasterKey(mk)

            val today = todaySeconds()
            val cred = getOrFetchCredentials(today) ?: run {
                Log.w(TAG, "No credential for today; aborting group fetch")
                return null
            }
            val aci = credentials.aci ?: return null
            val pni = credentials.pni ?: return null
            val auth: GroupsV2AuthorizationString =
                groupsApi.getGroupsV2AuthorizationString(aci, pni, today, params, cred)

            groupsApi.getGroup(params, auth).group
        } catch (e: Exception) {
            Log.w(TAG, "fetchGroup failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Extract the member ACIs from a decrypted group. The aciBytes field
     * is libsignal's binary ServiceId format; ServiceId.parseOrNull does
     * the right thing across PNI/ACI prefixes.
     */
    fun memberAcis(group: DecryptedGroup): List<ServiceId.ACI> {
        return group.members.mapNotNull { m ->
            val bytes = m.aciBytes.toByteArray()
            try {
                ServiceId.parseOrNull(bytes) as? ServiceId.ACI
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun getOrFetchCredentials(daySeconds: Long): AuthCredentialWithPniResponse? {
        credentialsByDay[daySeconds]?.let { return it }
        return try {
            // Server returns the current week of credentials in one call.
            // Cache them all so subsequent fetches in the same window are free.
            val maps = groupsApi.getCredentials(daySeconds)
            val responses = maps.authCredentialWithPniResponseHashMap
            credentialsByDay.putAll(responses)
            responses[daySeconds]
        } catch (e: Exception) {
            Log.w(TAG, "Credential fetch failed for day $daySeconds: ${e.message}")
            null
        }
    }

    private fun todaySeconds(): Long =
        (System.currentTimeMillis() / 1000L / DAY_SECONDS) * DAY_SECONDS
}
