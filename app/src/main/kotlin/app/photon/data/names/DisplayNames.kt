package app.photon.data.names

import android.telephony.PhoneNumberUtils
import app.photon.data.model.Conversation
import app.photon.data.model.Platform
import java.util.Locale

/**
 * Every name Photon shows for a person or a chat follows one policy, the same
 * on every platform ("LP3 contacts first"). For a person:
 *
 *  1. the LP3's own contact for their number ([LocalContacts]),
 *  2. the platform's address-book name (WhatsApp: the contact name synced
 *     from the primary; Signal: the primary's contact sync),
 *  3. a verified business name,
 *  4. their profile or push name,
 *  5. their number, formatted for the device's country,
 *  6. "WhatsApp contact", "Signal contact" or "Unknown".
 *
 * A group shows its subject, else "WhatsApp group" / "Signal group". The only
 * other names are an SMS alphanumeric sender (shown as sent), [YOU] and
 * [NOTE_TO_SELF]. Raw JIDs, LID digits, ACIs and base64 group ids are never
 * shown: a stored "name" that looks like one is skipped as if it were blank.
 *
 * Repositories store the sources and resolve with this when they read, so a
 * change of policy here needs no migration.
 */
object DisplayNames {
    const val YOU = "You"
    const val NOTE_TO_SELF = "Note to Self"

    /**
     * ISO 3166 country for numbers written without a country code. Photon sets
     * it at startup from the SIM or network ([deviceCountry]).
     */
    @Volatile var defaultCountry: String = Locale.getDefault().country

    /** A person nothing names. */
    fun placeholder(platform: Platform): String = when (platform) {
        Platform.WHATSAPP -> "WhatsApp contact"
        Platform.SIGNAL -> "Signal contact"
        Platform.SMS -> "Unknown"
    }

    /** A group without a subject. SMS has no groups. */
    fun groupPlaceholder(platform: Platform): String = when (platform) {
        Platform.WHATSAPP -> "WhatsApp group"
        Platform.SIGNAL -> "Signal group"
        Platform.SMS -> "Unknown"
    }

    /** A person, from their name sources in policy order. */
    fun person(
        platform: Platform,
        lp3: String? = null,
        addressBook: String? = null,
        business: String? = null,
        profile: String? = null,
        phone: String? = null,
    ): String =
        sequenceOf(lp3, addressBook, business, profile).firstNotNullOfOrNull { asName(it) }
            ?: phone?.takeIf { looksLikePhone(it) }?.let { number(it) }
            ?: placeholder(platform)

    /** A group: its subject, else the platform's placeholder. */
    fun group(platform: Platform, subject: String?, jid: String? = null): String =
        subject?.trim()?.takeIf { it.isNotEmpty() && it != jid && !isRawId(it) }
            ?: groupPlaceholder(platform)

    /**
     * A chat's title. [Conversation.name] holds what its repository resolved
     * (with [person] or [group]); this is the last guard before the screen,
     * so a missing or raw name still never shows an id.
     */
    fun title(conv: Conversation, platform: Platform): String =
        title(conv.jid, conv.name, conv.isGroup, platform)

    /** [title] for a chat known only by its id and stored name. */
    fun title(jid: String, name: String?, isGroup: Boolean, platform: Platform): String {
        if (isGroup) return group(platform, name, jid)
        asName(name, jid)?.let { return it }
        // A resolved (or legacy, unformatted) number — never an @lid's digits.
        if (name != null && looksLikePhone(name) && !jid.endsWith("@lid")) return number(name)
        phoneOf(jid, platform)?.let { return number(it) }
        // SMS keys an alphanumeric sender ("HSBC UK") by its name.
        if (platform == Platform.SMS && jid.isNotBlank()) return jid.trim()
        return placeholder(platform)
    }

    /** Who sent a message in a group: [YOU], their resolved [name], else what their id allows. */
    fun sender(platform: Platform, isFromMe: Boolean, name: String?, senderJid: String): String =
        if (isFromMe) YOU else title(senderJid, name, isGroup = false, platform)

    /**
     * What the contact editor's name field starts with: [displayName] when it
     * is a real name, or empty for a number or a placeholder, which would
     * otherwise be saved as the contact's name.
     */
    fun contactPrefill(displayName: String?): String {
        val name = asName(displayName) ?: return ""
        return if (name == YOU || name == NOTE_TO_SELF) "" else name
    }

    /** [phone] formatted for [country] ("+44 7700 900123"), or as given if it won't parse. */
    fun number(phone: String, country: String = defaultCountry): String {
        val trimmed = phone.trim()
        return try {
            PhoneNumberUtils.formatNumber(trimmed, country.uppercase(Locale.ROOT))
        } catch (_: Exception) {
            null
        } ?: trimmed
    }

    /** The number a chat id carries, if any: a WhatsApp phone JID, or a number-keyed Signal / SMS chat. */
    fun phoneOf(jid: String, platform: Platform): String? = when (platform) {
        Platform.WHATSAPP -> jid.takeIf { it.endsWith("@s.whatsapp.net") }
            ?.substringBefore("@")?.substringBefore(":")?.substringBefore(".")
            ?.takeIf { it.length >= 5 && it.all(Char::isDigit) }
            ?.let { "+$it" }
        Platform.SIGNAL, Platform.SMS -> jid.trim().takeIf { looksLikePhone(it) }
    }

    /** Digits with the usual punctuation, as a number is written. */
    fun looksLikePhone(s: String): Boolean {
        val t = s.trim()
        if (!PHONE_CHARS.matches(t)) return false
        return t.count(Char::isDigit) in 3..15
    }

    /**
     * [s] as a name: not blank, not [jid] itself, not an id, not a number and
     * not a placeholder (older bridges sent "WhatsApp contact" as a caller's name).
     */
    private fun asName(s: String?, jid: String? = null): String? {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (t == jid || isRawId(t) || looksLikePhone(t) || t in PLACEHOLDERS) return null
        return t
    }

    /** A JID, ACI / PNI or base64 group id. */
    internal fun isRawId(s: String): Boolean =
        (s.contains('@') && s.none(Char::isWhitespace)) || SERVICE_ID.matches(s) || BASE64_ID.matches(s)

    private val PHONE_CHARS = Regex("""^\+?[0-9(][0-9 ().\-]*$""")
    private val SERVICE_ID = Regex(
        """^(?i)(?:PNI:|ACI:)?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""",
    )
    private val BASE64_ID = Regex("""^[A-Za-z0-9+/_\-]{40,}={0,2}$""")

    private val PLACEHOLDERS = Platform.entries.flatMap { listOf(placeholder(it), groupPlaceholder(it)) }.toSet()
}

/** The device's country for number formatting: the SIM's, else the network's, else the locale's. */
fun deviceCountry(context: android.content.Context): String {
    val telephony = try {
        context.getSystemService(android.telephony.TelephonyManager::class.java)
    } catch (_: Exception) {
        null
    }
    return sequenceOf(
        { telephony?.simCountryIso },
        { telephony?.networkCountryIso },
        { Locale.getDefault().country },
    ).mapNotNull { read -> try { read() } catch (_: Exception) { null } }
        .firstOrNull { it.isNotBlank() }
        ?.uppercase(Locale.ROOT)
        ?: ""
}
