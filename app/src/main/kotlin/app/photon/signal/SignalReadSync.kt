package app.photon.signal

import org.signal.core.models.ServiceId
import java.util.concurrent.ConcurrentHashMap

/**
 * What a read sync for a set of seen rows will actually carry. [entries] are
 * the (author ACI, sent timestamp) pairs other devices use to locate each
 * message; [rowIds] are exactly the rows those entries cover. [skippedIds]
 * can't be expressed in a read sync — malformed ids, or authors stored under
 * a PNI/raw id because no ACI mapping existed yet — and must stay unsynced
 * rather than be marked read without anything going out.
 */
internal data class ReadSyncPlan(
    val entries: List<Pair<ServiceId.ACI, Long>>,
    val rowIds: List<String>,
    val skippedIds: List<String>,
)

internal fun planReadSync(pendingIds: List<String>): ReadSyncPlan {
    // A set: a message stored as several rows (one per attachment) is one
    // entry, covering all of them.
    val entries = linkedSetOf<Pair<ServiceId.ACI, Long>>()
    val rowIds = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    for (id in pendingIds) {
        val parsed = MessageKeys.parse(id)
        val aci = parsed?.let { ServiceId.ACI.parseOrNull(it.first) }
        if (parsed == null || aci == null) {
            skipped.add(id)
        } else {
            entries.add(aci to parsed.second)
            rowIds.add(id)
        }
    }
    return ReadSyncPlan(entries.toList(), rowIds, skipped)
}

/**
 * Remembers which conversations have rows a read sync can't express, so
 * that is logged once per conversation instead of on every chat open. Such
 * rows stay pending: once their author's ACI is known the conversation is
 * merged and they sync.
 */
internal class UnsyncableReads {
    private val reported = ConcurrentHashMap.newKeySet<String>()

    /** True the first time [conversationJid]'s plan skips rows. */
    fun firstReport(conversationJid: String, plan: ReadSyncPlan): Boolean =
        plan.skippedIds.isNotEmpty() && reported.add(conversationJid)
}
