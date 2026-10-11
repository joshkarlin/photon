package app.photon.data.names

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Numbers compared as numbers. Robolectric for the framework's number
 * parser. National numbers use libphonenumber's example UK mobile range
 * (07400 123456): it treats Ofcom's drama range (07700 900xxx) as invalid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PhoneNumbersTest {
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

    @Test
    fun numbersAreReadForTheDeviceCountry() {
        assertEquals("+447400123456", PhoneNumbers.e164("07400 123456"))
        assertEquals("+447400123456", PhoneNumbers.e164("+44 7400 123-456"))
        assertEquals("+16502530000", PhoneNumbers.e164("650-253-0000", country = "US"))
        assertEquals("+16502530000", PhoneNumbers.e164("+1 650 253 0000"))
    }

    @Test
    fun whatIsntAValidNumberHasNoE164() {
        assertNull(PhoneNumbers.e164("Mo"))
        assertNull(PhoneNumbers.e164("HSBC UK"))
        assertNull(PhoneNumbers.e164("12"))
        assertNull(PhoneNumbers.e164("07400"))
        assertNull(PhoneNumbers.e164("07700 900123"), "the framework doesn't know the drama range")
    }

    @Test
    fun anInternationalNumberTheFrameworkDoesntKnowIsTakenAsWritten() {
        assertEquals("+447700900123", PhoneNumbers.e164("+44 7700 900123"))
        assertEquals("+99912345678", PhoneNumbers.e164("+999 1234 5678"))
        assertNull(PhoneNumbers.e164("+999 12"))
    }

    @Test
    fun theSameNumberWrittenTwoWays() {
        assertTrue(PhoneNumbers.same("+447400123456", "07400 123456"))
        assertTrue(PhoneNumbers.same("+447700900123", "+44 7700 900-123"))
        assertFalse(PhoneNumbers.same("+447400123456", "07400 123457"))
        assertTrue(PhoneNumbers.same("12345", "1-2-3-4-5"), "short codes compare by digits")
        assertFalse(PhoneNumbers.same("HSBC UK", "Specsavers"))
    }

    @Test
    fun typedDigitsAreFoundInternationallyOrNationally() {
        val forms = PhoneNumbers.digitForms("07400 123456")
        assertEquals(setOf("07400123456", "447400123456"), forms)
        fun found(typed: String) =
            PhoneNumbers.typedForms(typed.filter(Char::isDigit)).any { t -> forms.any { it.contains(t) } }
        assertTrue(found("+44 7400"))
        assertTrue(found("0044 7400"))
        assertTrue(found("07400"))
        assertTrue(found("123 456"))
        assertFalse(found("12"), "too few digits")
        assertFalse(found("07401"))

        // Saved internationally, found by its national digits.
        val international = PhoneNumbers.digitForms("+44 7700 900123")
        assertTrue(PhoneNumbers.typedForms("07700").any { t -> international.any { it.contains(t) } })
    }
}
