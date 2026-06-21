package app.photon.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageKeysTest {

    @Test
    fun prefixStripsRandomSuffix() {
        assertEquals(
            "aci-uuid_1700000000000",
            MessageKeys.prefixOf("aci-uuid_1700000000000_ab12cd34"),
        )
    }

    @Test
    fun parseExtractsAuthorAndTimestamp() {
        assertEquals(
            "aci-uuid" to 1700000000000L,
            MessageKeys.parse("aci-uuid_1700000000000_ab12cd34"),
        )
    }

    @Test
    fun parseRejectsNonNumericTimestamp() {
        assertNull(MessageKeys.parse("author_notanumber_rand"))
    }

    @Test
    fun parseRejectsNoSeparators() {
        assertNull(MessageKeys.parse("noseparators"))
    }

    @Test
    fun parseRejectsEmptyAuthor() {
        assertNull(MessageKeys.parse("_1700000000000_rand"))
    }

    @Test
    fun remapAttachesReactionsToEveryFullIdSharingThePrefix() {
        val ids = listOf("a_100_x", "a_100_y", "b_200_z")
        val byPrefix = mapOf("a_100" to listOf("👍"), "b_200" to listOf("❤️"))

        val out = MessageKeys.remapByPrefix(ids, byPrefix)

        // This is the exact bug class that was fixed: a reaction stored under
        // "a_100" must reach BOTH full ids that share that prefix.
        assertEquals(listOf("👍"), out["a_100_x"])
        assertEquals(listOf("👍"), out["a_100_y"])
        assertEquals(listOf("❤️"), out["b_200_z"])
    }

    @Test
    fun remapDropsPrefixesWithNoMatchingMessage() {
        val out = MessageKeys.remapByPrefix(listOf("a_100_x"), mapOf("z_999" to listOf("👍")))
        assertEquals(0, out.size)
    }
}
