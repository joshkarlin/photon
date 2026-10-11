package app.photon.signal.receiver

import android.util.Log
import app.photon.signal.ConversationNaming
import app.photon.signal.SignalGroupV2Manager
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.getConversation
import app.photon.signal.db.getGroupMeta
import app.photon.signal.db.getGroupsNeedingMetadataRefresh
import app.photon.signal.db.memberDisplayName
import app.photon.signal.db.updateGroupMeta
import app.photon.signal.db.upsertConversation
import app.photon.signal.db.upsertParticipant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = SignalMessageReceiver.TAG

// Conversation name used until the GroupV2 state fetch resolves the
// real title. Also doubles as the "needs fetch" sentinel.
private const val PLACEHOLDER_GROUP_NAME = ConversationNaming.PLACEHOLDER_GROUP_NAME

/** GroupV2 conversations: master keys, titles and member lists. */
internal class GroupMetadata(
    private val messageDb: SignalMessageDatabase,
    private val lp3: (String) -> String?,
    private val scope: CoroutineScope,
    private val groupManager: () -> SignalGroupV2Manager?,
    private val sessionPings: SessionPings,
) {
    /**
     * Repair/refetch GroupV2 metadata after reconnect. Blank titles need
     * the old name-wipe repair; empty participant tables need a refetch so
     * group-only members can be session-primed below.
     */
    fun refreshGroupsNeedingMetadata() {
        try {
            val groups = messageDb.getGroupsNeedingMetadataRefresh()
            if (groups.isNotEmpty()) {
                Log.i(TAG, "Refreshing metadata for ${groups.size} Signal group(s)")
                groups.forEach { (jid, masterKey) ->
                    val currentName = messageDb.getConversation(jid)?.name
                    if (currentName.isNullOrBlank()) {
                        messageDb.upsertConversation(jid = jid, name = PLACEHOLDER_GROUP_NAME, isGroup = true)
                    }
                    resolveGroupMetadata(jid, masterKey)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Group metadata refresh failed: ${e.message}")
        }
    }

    /**
     * Seed (or refresh) a GroupV2 conversation row from a message that
     * carries the group's master key. Plants a placeholder name on first
     * sight, persists masterKey + revision, and kicks off an async state
     * fetch whenever the name is still unresolved or the revision moved.
     * Shared by the incoming-data and sync-sent paths.
     */
    fun seedGroupConversation(conversationJid: String, masterKey: ByteArray, revision: Int) {
        val existingName = messageDb.getConversation(conversationJid)?.name
        val placeholder = existingName?.takeIf { it.isNotBlank() && it != conversationJid }
            ?: PLACEHOLDER_GROUP_NAME
        messageDb.upsertConversation(jid = conversationJid, name = placeholder, isGroup = true)
        val storedRevision = messageDb.getGroupMeta(conversationJid)?.revision ?: -1
        messageDb.updateGroupMeta(conversationJid, masterKey, revision)
        val needsFetch = existingName.isNullOrBlank() ||
            existingName == PLACEHOLDER_GROUP_NAME ||
            existingName == conversationJid ||
            storedRevision < revision
        if (needsFetch) {
            Log.i(TAG, "Group ${conversationJid.take(12)}…: seeding masterKey + fetching state (rev=$revision)")
            scope.launch { resolveGroupMetadata(conversationJid, masterKey) }
        }
    }

    /**
     * Async-fetch the GroupV2 state from the server (title + members),
     * decrypt it via libsignal's GroupsV2Operations, and write back: the
     * conversation row's `name` becomes the decrypted title, and every
     * member ACI lands in the participants table so per-message sender
     * names show up in the chat. Failures are non-fatal — the placeholder
     * "Signal group" stays until the next message triggers another try.
     */
    private fun resolveGroupMetadata(conversationJid: String, masterKey: ByteArray) {
        val gm = groupManager() ?: run {
            Log.w(TAG, "Group fetch skipped: manager not ready for ${conversationJid.take(12)}…")
            return
        }
        val group = gm.fetchGroup(masterKey) ?: run {
            Log.w(TAG, "Group fetch returned null for ${conversationJid.take(12)}… — see GroupV2Manager warnings")
            return
        }
        val title = group.title?.takeIf { it.isNotBlank() } ?: PLACEHOLDER_GROUP_NAME
        messageDb.upsertConversation(jid = conversationJid, name = title, isGroup = true)

        val acis = gm.memberAcis(group)
        for (memberAci in acis) {
            val aciString = memberAci.toString()
            val name = messageDb.memberDisplayName(aciString, lp3)
            messageDb.upsertParticipant(conversationJid, aciString, name, "member")
        }
        Log.i(TAG, "Resolved group ${conversationJid.take(12)}…: $title (${acis.size} members)")
        sessionPings.scheduleActiveContactPing()
    }

    fun groupTitle(groupJid: String): String =
        ConversationNaming.groupTitle(messageDb.getConversation(groupJid)?.name, groupJid)
}
