package app.photon.service

import kotlin.test.Test
import kotlin.test.assertEquals

class GoBridgeTest {

    private fun flag(args: List<String>, name: String): String? =
        args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

    @Test
    fun retentionIsPassedOnSpawn() {
        val args = bridgeArgs("/lib/libgobridge.so", "/data/files", maxMessages = 200, maxDays = 30)
        assertEquals("/lib/libgobridge.so", args.first())
        assertEquals("/data/files", flag(args, "--data-dir"))
        assertEquals("200", flag(args, "--max-messages"))
        assertEquals("30", flag(args, "--max-days"))
        assertEquals(GoBridge.MEDIA_TTL_MINS.toString(), flag(args, "--media-ttl-mins"))
    }

    @Test
    fun restartBackoffDoublesToACapAndResets() {
        val backoff = RestartBackoff()
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000), List(8) { backoff.next() })
        backoff.reset()
        assertEquals(1_000L, backoff.next())
    }
}
