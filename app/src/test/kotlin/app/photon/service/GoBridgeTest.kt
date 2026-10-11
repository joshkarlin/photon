package app.photon.service

import kotlin.test.Test
import kotlin.test.assertEquals

class GoBridgeTest {

    private fun flag(args: List<String>, name: String): String? =
        args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

    @Test
    fun retentionIsPassedOnSpawn() {
        val args = bridgeArgs("/lib/libgobridge.so", "/data/files", 8765, maxMessages = 200, maxDays = 30)
        assertEquals("/lib/libgobridge.so", args.first())
        assertEquals("/data/files", flag(args, "--data-dir"))
        assertEquals("200", flag(args, "--max-messages"))
        assertEquals("30", flag(args, "--max-days"))
        assertEquals(GoBridge.MEDIA_TTL_MINS.toString(), flag(args, "--media-ttl-mins"))
    }
}
