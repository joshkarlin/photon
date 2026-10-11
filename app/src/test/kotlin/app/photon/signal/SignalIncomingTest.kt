package app.photon.signal

import okio.ByteString.Companion.toByteString
import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.internal.push.DataMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignalIncomingTest {

    private val author = "00000000-0000-4000-8000-000000000001"
    private val reactor = "00000000-0000-4000-8000-000000000002"

    @Test
    fun reactionSetsEmojiOnTarget() {
        val update = incomingReactionFor(
            DataMessage.Reaction(emoji = "👍", targetAuthorAci = author, targetSentTimestamp = 1700),
            fallbackAuthor = reactor,
        )

        assertEquals(IncomingReaction.Set("${author}_1700", "👍"), update)
    }

    /** The remove flag withdraws the reaction; it used to be stored as a new one. */
    @Test
    fun removeFlagWithdrawsReaction() {
        val update = incomingReactionFor(
            DataMessage.Reaction(emoji = "👍", remove = true, targetAuthorAci = author, targetSentTimestamp = 1700),
            fallbackAuthor = reactor,
        )

        assertEquals(IncomingReaction.Remove("${author}_1700"), update)
    }

    @Test
    fun binaryTargetAuthorIsUsedWhenStringIsMissing() {
        val binary = ServiceId.ACI.parseOrNull(author)!!.toByteArray().toByteString()
        val update = incomingReactionFor(
            DataMessage.Reaction(emoji = "❤️", targetAuthorAciBinary = binary, targetSentTimestamp = 1700),
            fallbackAuthor = reactor,
        )

        assertEquals(IncomingReaction.Set("${author}_1700", "❤️"), update)
    }

    @Test
    fun reactionWithoutTargetOrEmojiIsIgnored() {
        assertNull(incomingReactionFor(DataMessage.Reaction(emoji = "👍"), fallbackAuthor = reactor))
        assertNull(
            incomingReactionFor(
                DataMessage.Reaction(targetAuthorAci = author, targetSentTimestamp = 1700),
                fallbackAuthor = reactor,
            ),
        )
    }

    @Test
    fun serviceIdStringPrefersBinaryAndPassesThroughUnparseableStrings() {
        val binary = ServiceId.ACI.parseOrNull(author)!!.toByteArray().toByteString()
        assertEquals(author, serviceIdString(null, binary))
        assertEquals(author, serviceIdString("ignored", binary))
        assertEquals(author, serviceIdString(author, null))
        assertEquals("not-a-uuid", serviceIdString("not-a-uuid", null))
        assertNull(serviceIdString(null, null))
    }
}
