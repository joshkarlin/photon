package app.photon.data.names

import android.telephony.PhoneNumberUtils
import java.util.Locale

/**
 * A phone number however it's written (typed, saved in the LP3 address book,
 * stored by a platform), compared as the number it is. Numbers written
 * without a country code are read for the device's country
 * ([DisplayNames.defaultCountry]), with the framework's [PhoneNumberUtils].
 */
object PhoneNumbers {

    /**
     * [number] in E.164 ("+447700900123"), or null when it isn't a valid
     * number. One written with "+" that the framework doesn't know (a range
     * newer than its tables) is taken as written.
     */
    fun e164(number: String, country: String = DisplayNames.defaultCountry): String? {
        val t = number.trim()
        if (!DisplayNames.looksLikePhone(t)) return null
        val valid = try {
            PhoneNumberUtils.formatNumberToE164(t, country.uppercase(Locale.ROOT))
        } catch (_: Exception) {
            null
        }
        if (valid != null) return valid
        val digits = t.filter(Char::isDigit)
        return if (t.startsWith("+") && digits.length in 8..15) "+$digits" else null
    }

    /** Whether [a] and [b] are the same number, however each is written. */
    fun same(a: String, b: String, country: String = DisplayNames.defaultCountry): Boolean {
        val ea = e164(a, country)
        val eb = e164(b, country)
        if (ea != null || eb != null) return ea == eb
        val da = a.filter(Char::isDigit)
        return da.isNotEmpty() && da == b.filter(Char::isDigit)
    }

    /**
     * The digit strings typed digits are found in, for [number]: as written,
     * and international (E.164 without the "+"). A typed national number
     * ("07700 9") is found through [typedForms].
     */
    fun digitForms(number: String, country: String = DisplayNames.defaultCountry): Set<String> =
        setOfNotNull(
            number.filter(Char::isDigit).takeIf { it.isNotEmpty() },
            e164(number, country)?.drop(1),
        )

    /**
     * What typed [digits] may be part of, or nothing under [MIN_DIGITS]: the
     * digits, and without a leading trunk or international prefix ("07700"
     * is found in "447700900123", as is "0044 7700").
     */
    fun typedForms(digits: String): Set<String> {
        if (digits.length < MIN_DIGITS) return emptySet()
        val unprefixed = digits.trimStart('0')
        return if (unprefixed.length >= MIN_DIGITS) setOf(digits, unprefixed) else setOf(digits)
    }

    /** Fewer typed digits than this match too many numbers to be useful. */
    const val MIN_DIGITS = 3
}
