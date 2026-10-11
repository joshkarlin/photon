package app.photon.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConversationNamingTest {

    private val addressBook = mapOf("+15550001111" to "Alice")
    private fun resolve(phone: String): String? = addressBook[phone]

    @Test
    fun groupWithNoPhoneOrProfileResolvesToNull() {
        // The critical case: a group has no contact row. Null here MUST mean
        // "leave the existing title alone", which the caller now enforces.
        assertNull(ConversationNaming.resolveFromContact(null, null, ::resolve))
    }

    @Test
    fun addressBookNameWins() {
        assertEquals(
            "Alice",
            ConversationNaming.resolveFromContact("+15550001111", "ProfileBob", ::resolve),
        )
    }

    @Test
    fun fallsBackToProfileNameThenPhone() {
        assertEquals(
            "ProfileBob",
            ConversationNaming.resolveFromContact("+15559999999", "ProfileBob", ::resolve),
        )
        assertEquals(
            "+15559999999",
            ConversationNaming.resolveFromContact("+15559999999", null, ::resolve),
        )
    }

    @Test
    fun blankProfileNameIsIgnored() {
        assertNull(ConversationNaming.resolveFromContact(null, "   ", ::resolve))
    }

    /** Group notifications used to be titled "Signal group" even once the real name was known. */
    @Test
    fun groupTitleUsesStoredNameWithPlaceholderOnlyAsFallback() {
        assertEquals("Frothers Utd", ConversationNaming.groupTitle("Frothers Utd", "grpB64"))
        assertEquals("Signal group", ConversationNaming.groupTitle(null, "grpB64"))
        assertEquals("Signal group", ConversationNaming.groupTitle(" ", "grpB64"))
        // Old rows could hold the raw base64 id as the name.
        assertEquals("Signal group", ConversationNaming.groupTitle("grpB64", "grpB64"))
    }
}
