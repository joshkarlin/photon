package app.photon.signal

import kotlin.test.Test
import kotlin.test.assertEquals

class SignalReadSyncTest {

    private val aci = "00000000-0000-4000-8000-000000000001"
    private val pni = "PNI:00000000-0000-4000-8000-000000000002"

    /**
     * Only rows whose entry is in the payload may be marked synced. A PNI-keyed
     * row (no ACI mapping yet) or a malformed id is skipped, not silently
     * marked read alongside the rows that were actually sent.
     */
    @Test
    fun mixedListOnlyCoversExpressibleRows() {
        val plan = planReadSync(
            listOf("${aci}_1700000000123_ab", "${pni}_1700000000456_cd", "call_${aci}_9", "garbage"),
        )

        assertEquals(listOf(aci to 1700000000123L), plan.entries.map { it.first.toString() to it.second })
        assertEquals(listOf("${aci}_1700000000123_ab"), plan.rowIds)
        assertEquals(listOf("${pni}_1700000000456_cd", "call_${aci}_9", "garbage"), plan.skippedIds)
    }

    @Test
    fun nothingExpressibleMeansEmptyPayload() {
        val plan = planReadSync(listOf("${pni}_1700000000456_cd"))

        assertEquals(emptyList(), plan.entries)
        assertEquals(emptyList(), plan.rowIds)
    }

    /** Opening a chat with unsyncable rows warns once, not on every open. */
    @Test
    fun unsyncableRowsAreReportedOncePerConversation() {
        val reads = UnsyncableReads()
        val blocked = planReadSync(listOf("${pni}_1700000000456_cd"))
        val fine = planReadSync(listOf("${aci}_1700000000123_ab"))

        assertEquals(false, reads.firstReport("chat", fine))
        assertEquals(true, reads.firstReport("chat", blocked))
        assertEquals(false, reads.firstReport("chat", blocked))
        assertEquals(true, reads.firstReport("other chat", blocked))
    }
}
