package app.photon.data.names

import app.photon.data.model.Conversation
import app.photon.data.model.Platform
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The naming policy, on every platform. Robolectric for the framework's number formatter. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DisplayNamesTest {

    private val aci = "6f1e2d3c-4b5a-4968-8776-655443322110"
    private val groupId = "Z3JvdXAtaWQtZm9yLXRlc3RzLTMyLWJ5dGVzLWxvbmc="
    private var savedCountry = ""

    @Before
    fun setCountry() {
        savedCountry = DisplayNames.defaultCountry
        DisplayNames.defaultCountry = "GB"
    }

    @After
    fun restoreCountry() {
        DisplayNames.defaultCountry = savedCountry
    }

    private fun conv(jid: String, name: String?, isGroup: Boolean = false) = Conversation(
        jid = jid, name = name, isGroup = isGroup, lastMessageId = null, lastTimestamp = 0,
        unreadCount = 0, isMuted = false, avatarUrl = null, updatedAt = 0,
    )

    @Test
    fun aPersonTakesTheFirstSourceInPolicyOrder() {
        val all = DisplayNames.person(
            Platform.SIGNAL, lp3 = "Mum", addressBook = "Mother", business = "Mum Ltd",
            profile = "Jane", phone = "+447700900123",
        )
        assertEquals("Mum", all)
        assertEquals("Mother", DisplayNames.person(Platform.SIGNAL, addressBook = "Mother", profile = "Jane"))
        assertEquals("Mum Ltd", DisplayNames.person(Platform.WHATSAPP, business = "Mum Ltd", profile = "Jane"))
        assertEquals("Jane", DisplayNames.person(Platform.WHATSAPP, profile = "Jane", phone = "+447700900123"))
        assertEquals("+44 7700 900123", DisplayNames.person(Platform.WHATSAPP, phone = "+447700900123"))
        assertEquals("WhatsApp contact", DisplayNames.person(Platform.WHATSAPP))
        assertEquals("Signal contact", DisplayNames.person(Platform.SIGNAL))
        assertEquals("Unknown", DisplayNames.person(Platform.SMS))
    }

    /** Old rows stored numbers, ids and blanks as names: each falls through to the next source. */
    @Test
    fun idsNumbersAndBlanksAreNotNames() {
        assertEquals(
            "+44 7700 900123",
            DisplayNames.person(Platform.SIGNAL, lp3 = " ", addressBook = "+447700900123", profile = aci, phone = "+447700900123"),
        )
        assertEquals(
            "WhatsApp contact",
            DisplayNames.person(Platform.WHATSAPP, addressBook = "123456789012345@lid", profile = "123456789012345"),
        )
        assertEquals("Signal contact", DisplayNames.person(Platform.SIGNAL, profile = "PNI:$aci"))
    }

    @Test
    fun aGroupShowsItsSubjectOrThePlatformPlaceholder() {
        assertEquals("Book club", DisplayNames.group(Platform.SIGNAL, "Book club", groupId))
        assertEquals("Signal group", DisplayNames.group(Platform.SIGNAL, null, groupId))
        assertEquals("Signal group", DisplayNames.group(Platform.SIGNAL, groupId, groupId))
        assertEquals("WhatsApp group", DisplayNames.group(Platform.WHATSAPP, "", "1203@g.us"))
        assertEquals("WhatsApp group", DisplayNames.group(Platform.WHATSAPP, "1203@g.us", "1203@g.us"))
        // A subject made of digits is still a subject.
        assertEquals("2024", DisplayNames.group(Platform.WHATSAPP, "2024", "1203@g.us"))
    }

    @Test
    fun titlesNeverShowARawId() {
        assertEquals("+44 7700 900123", DisplayNames.title(conv("447700900123@s.whatsapp.net", null), Platform.WHATSAPP))
        assertEquals("+44 7700 900123", DisplayNames.title(conv("447700900123:3@s.whatsapp.net", ""), Platform.WHATSAPP))
        assertEquals("WhatsApp contact", DisplayNames.title(conv("123456789012345@lid", null), Platform.WHATSAPP))
        // An @lid row named with its own digits.
        assertEquals("WhatsApp contact", DisplayNames.title(conv("123456789012345@lid", "123456789012345"), Platform.WHATSAPP))
        assertEquals("WhatsApp group", DisplayNames.title(conv("1203@g.us", null, isGroup = true), Platform.WHATSAPP))
        assertEquals("Signal contact", DisplayNames.title(conv(aci, null), Platform.SIGNAL))
        assertEquals("Signal contact", DisplayNames.title(conv(aci, aci.take(8) + "-0000-4000-8000-000000000000"), Platform.SIGNAL))
        assertEquals("Signal group", DisplayNames.title(conv(groupId, groupId, isGroup = true), Platform.SIGNAL))
        assertEquals("Signal group", DisplayNames.title(conv(groupId, "Signal group", isGroup = true), Platform.SIGNAL))
    }

    @Test
    fun titlesKeepResolvedNamesAndFormatNumbers() {
        assertEquals("Mum", DisplayNames.title(conv(aci, "Mum"), Platform.SIGNAL))
        assertEquals(DisplayNames.NOTE_TO_SELF, DisplayNames.title(conv(aci, DisplayNames.NOTE_TO_SELF), Platform.SIGNAL))
        // Already formatted by the repository: unchanged.
        assertEquals("+44 7700 900123", DisplayNames.title(conv(aci, "+44 7700 900123"), Platform.SIGNAL))
        // A legacy number-as-name is formatted.
        assertEquals("+44 7700 900123", DisplayNames.title(conv(aci, "+447700900123"), Platform.SIGNAL))
        // A Signal chat keyed by the number itself.
        assertEquals("+44 7700 900123", DisplayNames.title(conv("+447700900123", null), Platform.SIGNAL))
    }

    @Test
    fun smsShowsAlphanumericSendersAsSentAndFormatsNumbers() {
        assertEquals("HSBC UK", DisplayNames.title(conv("HSBC UK", "HSBC UK"), Platform.SMS))
        assertEquals("HSBC UK", DisplayNames.title(conv("HSBC UK", null), Platform.SMS))
        assertEquals(DisplayNames.number("07700900123"), DisplayNames.title(conv("07700900123", null), Platform.SMS))
        assertEquals("Mum", DisplayNames.title(conv("+447700900123", "Mum"), Platform.SMS))
    }

    @Test
    fun numbersFormatForTheDeviceCountry() {
        assertEquals("+44 7700 900123", DisplayNames.number("+447700900123"))
        assertEquals("+1 650-253-0000", DisplayNames.number("+16502530000", country = "US"))
        // A national number keeps its digits, however the framework lays them out.
        assertEquals("07700900123", DisplayNames.number("07700900123").filter(Char::isDigit))
        assertEquals("+1 650-253-0000", DisplayNames.number("+16502530000"))
        // Unparseable input comes back as given.
        assertEquals("12", DisplayNames.number("12"))
    }

    @Test
    fun sendersAreYouTheirNameOrWhatTheirIdAllows() {
        assertEquals("You", DisplayNames.sender(Platform.WHATSAPP, isFromMe = true, name = "Me", senderJid = "x@s.whatsapp.net"))
        assertEquals("Alice", DisplayNames.sender(Platform.WHATSAPP, isFromMe = false, name = "Alice", senderJid = "447700900123@s.whatsapp.net"))
        assertEquals("+44 7700 900123", DisplayNames.sender(Platform.WHATSAPP, false, null, "447700900123@s.whatsapp.net"))
        assertEquals("WhatsApp contact", DisplayNames.sender(Platform.WHATSAPP, false, null, "123456789012345@lid"))
        assertEquals("Signal contact", DisplayNames.sender(Platform.SIGNAL, false, null, aci))
    }

    @Test
    fun theContactEditorIsOnlyPrefilledWithARealName() {
        assertEquals("Mum", DisplayNames.contactPrefill("Mum"))
        assertEquals("", DisplayNames.contactPrefill("+44 7700 900123"))
        assertEquals("", DisplayNames.contactPrefill("Signal contact"))
        assertEquals("", DisplayNames.contactPrefill("WhatsApp contact"))
        assertEquals("", DisplayNames.contactPrefill("Unknown"))
        assertEquals("", DisplayNames.contactPrefill(DisplayNames.NOTE_TO_SELF))
        assertEquals("", DisplayNames.contactPrefill(null))
    }

    @Test
    fun phoneShapes() {
        assertTrue(DisplayNames.looksLikePhone("+44 7700 900123"))
        assertTrue(DisplayNames.looksLikePhone("(650) 253-0000"))
        assertFalse(DisplayNames.looksLikePhone("HSBC UK"))
        assertFalse(DisplayNames.looksLikePhone("Room 101"))
        assertFalse(DisplayNames.looksLikePhone("+1234567890123456"))
        assertEquals("+447700900123", DisplayNames.phoneOf("447700900123@s.whatsapp.net", Platform.WHATSAPP))
        assertEquals(null, DisplayNames.phoneOf("123456789012345@lid", Platform.WHATSAPP))
        assertEquals(null, DisplayNames.phoneOf(aci, Platform.SIGNAL))
    }
}
