package app.photon.signal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EarlyReadCacheTest {

    private var now = 1_000_000L
    private val cache = EarlyReadCache(maxEntries = 3, ttlMs = 60_000, clock = { now })

    @Test
    fun readThatArrivedFirstAppliesOnceToItsMessage() {
        cache.remember("alice_1700")

        assertTrue(cache.consume("alice_1700"))
        assertFalse(cache.consume("alice_1700"))
        assertFalse(cache.consume("alice_1800"))
    }

    @Test
    fun expiredReadIsIgnored() {
        cache.remember("alice_1700")
        now += 60_001

        assertFalse(cache.consume("alice_1700"))
    }

    @Test
    fun oldestEntriesAreDroppedPastTheBound() {
        listOf("a_1", "a_2", "a_3", "a_4").forEach { cache.remember(it) }

        assertFalse(cache.consume("a_1"))
        assertTrue(cache.consume("a_2"))
        assertTrue(cache.consume("a_4"))
    }

    @Test
    fun expiredEntriesDontCrowdOutFreshOnes() {
        cache.remember("a_1")
        cache.remember("a_2")
        now += 60_001
        listOf("b_1", "b_2", "b_3").forEach { cache.remember(it) }

        assertTrue(cache.consume("b_1"))
        assertTrue(cache.consume("b_3"))
    }
}
