package app.photon.signal.receiver

import android.util.Log
import app.photon.data.model.Platform
import app.photon.data.names.DisplayNames
import app.photon.signal.GroupFetch
import app.photon.signal.SignalGroupV2Manager
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.getConversation
import app.photon.signal.db.getGroupMeta
import app.photon.signal.db.getGroupsNeedingMetadataRefresh
import app.photon.signal.db.markGroupStateUnavailable
import app.photon.signal.db.updateGroupMeta
import app.photon.signal.db.updateGroupTimer
import app.photon.signal.db.upsertConversation
import app.photon.signal.db.upsertParticipant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroup

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
     * group-only members can be session-primed below; a timer not read at
     * the group's latest revision needs one so Photon's messages carry it.
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
     * Async-fetch the GroupV2 state from the server (title, members, timer),
     * decrypt it via libsignal's GroupsV2Operations, and write back: the
     * conversation row's `name` becomes the decrypted title, the
     * disappearing-messages timer is stored (a change adds its line), and
     * every member ACI lands in the participants table (for sends, session
     * pings and sender names). Failures are non-fatal — the group shows as
     * "Signal group" until the next message or reconnect tries again.
     */
    private fun resolveGroupMetadata(conversationJid: String, masterKey: ByteArray) {
        val gm = groupManager() ?: run {
            Log.w(TAG, "Group fetch skipped: manager not ready for ${conversationJid.take(12)}…")
            return
        }
        if (applyGroupFetch(conversationJid, gm.fetchGroupState(masterKey))) sessionPings.scheduleActiveContactPing()
    }

    /**
     * Store what a group fetch came to. A group the server won't give this
     * account (it left or was removed, or the group is gone) isn't fetched
     * again for its timer until a later revision shows up; any other
     * failure is tried again on the next connect. Returns whether the state
     * was read.
     */
    internal fun applyGroupFetch(conversationJid: String, fetch: GroupFetch): Boolean = when (fetch) {
        is GroupFetch.Fetched -> {
            applyGroupState(conversationJid, fetch.group)
            true
        }
        GroupFetch.Unavailable -> {
            Log.i(TAG, "Group ${conversationJid.take(12)}… isn't readable by this account; not fetching it again until it changes")
            messageDb.markGroupStateUnavailable(conversationJid)
            false
        }
        GroupFetch.Failed -> {
            Log.w(TAG, "Group fetch failed for ${conversationJid.take(12)}… — see GroupV2Manager warnings")
            false
        }
    }

    /** Store what a fetched group state says: title, disappearing-messages timer and members. */
    internal fun applyGroupState(conversationJid: String, group: DecryptedGroup) {
        val title = group.title?.takeIf { it.isNotBlank() }
        messageDb.upsertConversation(jid = conversationJid, name = title, isGroup = true)
        // The group's disappearing-messages timer: the only source Signal
        // uses for a group (it ignores the timer on group messages).
        messageDb.updateGroupTimer(conversationJid, group.disappearingMessagesTimer?.duration ?: 0, group.revision)

        val acis = SignalGroupV2Manager.memberAcis(group)
        for (memberAci in acis) messageDb.upsertParticipant(conversationJid, memberAci.toString())
        Log.i(TAG, "Resolved group ${conversationJid.take(12)}…: $title (${acis.size} members)")
    }
}
