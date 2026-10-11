package app.photon.signal.receiver

import android.util.Log
import app.photon.data.model.Platform
import app.photon.data.names.DisplayNames
import app.photon.signal.SignalGroupV2Manager
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.getConversation
import app.photon.signal.db.getGroupMeta
import app.photon.signal.db.getGroupsNeedingMetadataRefresh
import app.photon.signal.db.updateGroupMeta
import app.photon.signal.db.upsertConversation
import app.photon.signal.db.upsertParticipant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = SignalMessageReceiver.TAG

// Older builds stored this as a group's name until its title was fetched;
// such a group still needs fetching.
private val LEGACY_PLACEHOLDER = DisplayNames.groupPlaceholder(Platform.SIGNAL)

/** GroupV2 conversations: master keys, titles and member lists. */
internal class GroupMetadata(
    private val messageDb: SignalMessageDatabase,
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
                groups.forEach { (jid, masterKey) -> resolveGroupMetadata(jid, masterKey) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Group metadata refresh failed: ${e.message}")
        }
    }

    /**
     * Seed (or refresh) a GroupV2 conversation row from a message that
     * carries the group's master key. Creates the row on first sight (shown
     * as "Signal group" until it has a subject), persists masterKey +
     * revision, and kicks off an async state fetch whenever the subject is
     * still unknown or the revision moved. Shared by the incoming-data and
     * sync-sent paths.
     */
    fun seedGroupConversation(conversationJid: String, masterKey: ByteArray, revision: Int) {
        val existingName = messageDb.getConversation(conversationJid)?.name
        messageDb.upsertConversation(jid = conversationJid, name = null, isGroup = true)
        val storedRevision = messageDb.getGroupMeta(conversationJid)?.revision ?: -1
        messageDb.updateGroupMeta(conversationJid, masterKey, revision)
        val needsFetch = existingName.isNullOrBlank() ||
            existingName == LEGACY_PLACEHOLDER ||
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
     * member ACI lands in the participants table (for sends, session pings
     * and sender names). Failures are non-fatal — the group shows as
     * "Signal group" until the next message or reconnect tries again.
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
        val title = group.title?.takeIf { it.isNotBlank() }
        messageDb.upsertConversation(jid = conversationJid, name = title, isGroup = true)

        val acis = gm.memberAcis(group)
        for (memberAci in acis) messageDb.upsertParticipant(conversationJid, memberAci.toString())
        Log.i(TAG, "Resolved group ${conversationJid.take(12)}…: $title (${acis.size} members)")
        sessionPings.scheduleActiveContactPing()
    }
}
