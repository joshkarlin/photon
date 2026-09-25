package app.photon.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SignalReactionTest {
    @Test
    fun reactionTargetsAuthorAndWireTimestampFromMessageId() {
        val author = "00000000-0000-4000-8000-000000000001"
        val reaction = signalReactionFor("${author}_1700000000123_abcd", "🤙")!!

        assertEquals("🤙", reaction.emoji)
        assertEquals(author, reaction.targetAuthor.toString())
        assertEquals(1700000000123L, reaction.targetSentTimestamp)
        assertFalse(reaction.isRemove)
    }

    @Test
    fun rejectsInvalidTargetOrEmptyEmoji() {
        assertNull(signalReactionFor("invalid", "🤙"))
        assertNull(signalReactionFor("00000000-0000-4000-8000-000000000001_123_abcd", ""))
    }
}
