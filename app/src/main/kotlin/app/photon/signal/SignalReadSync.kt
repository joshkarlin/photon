package app.photon.signal

import org.signal.core.models.ServiceId

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
    val entries = mutableListOf<Pair<ServiceId.ACI, Long>>()
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
    return ReadSyncPlan(entries, rowIds, skipped)
}
