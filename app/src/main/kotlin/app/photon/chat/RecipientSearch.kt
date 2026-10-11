package app.photon.chat

import app.photon.data.model.Conversation
import app.photon.data.model.Platform
import app.photon.data.names.DisplayNames
import app.photon.data.names.LocalContact
import app.photon.data.names.PhoneNumbers
import java.text.Normalizer
import java.util.Locale

// Who New message can write to, and how the "To" field finds them. Pure:
// the screen hands it the LP3 contacts and every platform's chats.

/** A chat New message can find: its [platform], and the number it's with, when known. */
data class KnownChat(val platform: Platform, val conversation: Conversation, val phone: String?) {
    /** Its title, as the chat list shows it. */
    val title: String get() = DisplayNames.title(conversation, platform)
}

/**
 * Someone to write to: an LP3 contact and their chats on every platform,
 * or the chats with a number nobody saved, or a chat with no number (an
 * SMS sender such as "HSBC UK", a WhatsApp chat known only by its LID).
 *
 * @param phones their numbers, in E.164 where they parse, else as saved
 * @param chats their DMs, newest first
 */
data class Person(val name: String, val phones: List<String>, val chats: List<KnownChat>) {
    /** When they last wrote or were written to; 0 for no chat. */
    val lastActivity: Long get() = chats.maxOfOrNull { it.conversation.lastTimestamp } ?: 0
}

/** One row of New message's matches. */
sealed interface RecipientMatch {
    /** The query is a phone number: "Send to +44 7700 900123". */
    data class Number(val e164: String) : RecipientMatch

    /** [matchedPhone] is set when the query's digits found them, rather than their name. */
    data class PersonMatch(val person: Person, val matchedPhone: String? = null) : RecipientMatch

    data class GroupMatch(val chat: KnownChat) : RecipientMatch
}

/**
 * The LP3 [contacts] and every platform's [chats], merged into people:
 * a contact takes the chats with any of their numbers; chats with the same
 * number nobody saved are one person, named by their newest chat; a chat
 * without a number is a person of its own. Groups are kept apart.
 *
 * @param country for numbers written without a country code
 */
class RecipientIndex(
    contacts: List<LocalContact>,
    chats: List<KnownChat>,
    private val country: String = DisplayNames.defaultCountry,
) {
    private class Entry(
        val person: Person,
        /** Their names, folded: the contact's and every chat's title. */
        val names: List<String>,
        /** Each number, with the digit strings typed digits are found in. */
        val numbers: List<Pair<String, Set<String>>>,
    )

    private val entries: List<Entry>
    private val byNumber: Map<String, Person>

    /** Group chats, newest first. */
    val groups: List<KnownChat>

    /** Everyone, contacts first in address-book order, then chats nobody saved, newest first. */
    val people: List<Person> get() = entries.map { it.person }

    init {
        class Builder(val name: String?, val phones: MutableList<String>) {
            val chats = mutableListOf<KnownChat>()
        }
        val builders = mutableListOf<Builder>()
        val byKey = HashMap<String, Builder>()
        for (contact in contacts) {
            val builder = Builder(contact.name, contact.phones.map(::canonical).distinct().toMutableList())
            builders += builder
            builder.phones.forEach { byKey.putIfAbsent(key(it), builder) }
        }
        val groupChats = mutableListOf<KnownChat>()
        for (chat in chats.sortedByDescending { it.conversation.lastTimestamp }) {
            if (chat.conversation.isGroup) {
                groupChats += chat
                continue
            }
            val phone = chat.phone?.takeIf { DisplayNames.looksLikePhone(it) }
            val builder = phone?.let { byKey[key(it)] }
                ?: Builder(null, listOfNotNull(phone?.let(::canonical)).toMutableList()).also { new ->
                    builders += new
                    phone?.let { byKey[key(it)] = new }
                }
            builder.chats += chat
        }
        groups = groupChats
        entries = builders.map { b ->
            val titles = b.chats.map { it.title }
            // Named by the contact, else by the newest chat that has a real
            // name, else as the newest chat is titled (a number, a sender).
            val name = b.name
                ?: titles.firstOrNull { !DisplayNames.looksLikePhone(it) && it !in placeholders }
                ?: titles.firstOrNull()
                ?: b.phones.firstOrNull()?.let { DisplayNames.number(it, country) }
                ?: ""
            Entry(
                person = Person(name, b.phones, b.chats),
                names = (listOf(name) + titles).distinct().map(::fold),
                numbers = b.phones.map { it to PhoneNumbers.digitForms(it, country) },
            )
        }
        byNumber = entries.flatMap { e -> e.person.phones.map { key(it) to e.person } }.toMap()
    }

    /**
     * The matches for what's typed in the "To" field, empty for nothing
     * typed: a "Send to" row when it's a phone number, then people, then
     * groups whose name matches. A name matches case- and accent-blind
     * anywhere in it, best where it starts; digits match within any of a
     * person's numbers, written internationally or nationally.
     */
    fun search(query: String): List<RecipientMatch> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val folded = fold(q)
        val numberish = NUMBERISH.matches(q)
        val typed = if (numberish) PhoneNumbers.typedForms(q.filter(Char::isDigit)) else emptySet()

        val people = entries.mapNotNull { e ->
            val nameRank = e.names.mapNotNull { nameRank(it, folded) }.minOrNull()
            when {
                nameRank != null -> Ranked(RecipientMatch.PersonMatch(e.person), nameRank, e.person.lastActivity, e.person.name)
                typed.isEmpty() -> null
                else -> e.numbers.firstOrNull { (_, forms) -> typed.any { t -> forms.any { it.contains(t) } } }
                    ?.let { (phone, _) -> Ranked(RecipientMatch.PersonMatch(e.person, phone), DIGITS, e.person.lastActivity, e.person.name) }
            }
        }
        val groups = groups.mapNotNull { chat ->
            nameRank(fold(chat.title), folded)?.let { Ranked(RecipientMatch.GroupMatch(chat), it, chat.conversation.lastTimestamp, chat.title) }
        }
        val number = if (numberish) PhoneNumbers.e164(q, country)?.let(RecipientMatch::Number) else null
        return listOfNotNull(number) + best(people, MAX_PEOPLE) + best(groups, MAX_GROUPS)
    }

    /** Whoever has [phone], else someone new known only by it. */
    fun personFor(phone: String): Person =
        byNumber[key(phone)] ?: Person(DisplayNames.number(phone, country), listOf(canonical(phone)), emptyList())

    private class Ranked(val match: RecipientMatch, val rank: Int, val recency: Long, val name: String)

    private fun best(ranked: List<Ranked>, max: Int): List<RecipientMatch> =
        ranked.sortedWith(compareBy<Ranked>({ it.rank }, { -it.recency }, { it.name.lowercase(Locale.ROOT) }))
            .take(max)
            .map { it.match }

    private fun canonical(phone: String): String = PhoneNumbers.e164(phone, country) ?: phone.trim()

    /** One key per number however it's written. */
    private fun key(phone: String): String = PhoneNumbers.e164(phone, country) ?: phone.filter(Char::isDigit)

    companion object {
        /** Typed as a number might be: digits and the usual punctuation. */
        private val NUMBERISH = Regex("""^\+?[0-9][0-9 ().\-]*$""")

        private const val DIGITS = 3
        private const val MAX_PEOPLE = 50
        private const val MAX_GROUPS = 20

        private val placeholders = Platform.entries.map(DisplayNames::placeholder).toSet()

        /** [s] for comparing: lower case, without accents ("Zoë" is "zoe"). */
        internal fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(MARKS, "")
                .lowercase(Locale.ROOT)

        private val MARKS = Regex("""\p{Mn}+""")

        /**
         * How well [query] matches [name] (both folded): 0 at its start, 1 at
         * the start of a later word, 2 anywhere else, null not at all.
         */
        internal fun nameRank(name: String, query: String): Int? {
            var at = name.indexOf(query)
            if (at < 0) return null
            if (at == 0) return 0
            while (at > 0) {
                if (!name[at - 1].isLetterOrDigit()) return 1
                at = name.indexOf(query, at + 1)
            }
            return 2
        }
    }
}

/**
 * The chat to open at once for [person]: their only one, or the newest
 * where they have chats on just one platform. Null when they have chats on
 * more than one, or none: then "Send via" asks.
 */
fun directChat(person: Person): KnownChat? =
    person.chats.map { it.platform }.distinct().singleOrNull()?.let { platform -> person.chats.first { it.platform == platform } }

/** One "Send via" row: [reach] on [platform] (null while it's checked), by [phone] when it was a number. */
data class SendViaOption(val platform: Platform, val reach: Reach?, val phone: String? = null)

/**
 * Where [person] can be messaged on [platform]: their chat there, else the
 * first of their numbers [reach] can take, else why not (the first number's
 * reason). A person without a number can only be reached through a chat.
 */
suspend fun reachOn(platform: Platform, person: Person, reach: suspend (phone: String) -> Reach): SendViaOption {
    person.chats.firstOrNull { it.platform == platform }?.let {
        return SendViaOption(platform, Reach.Reachable(it.conversation.jid, existing = true))
    }
    var unreachable: Reach.Unreachable? = null
    for (phone in person.phones) {
        when (val r = reach(phone)) {
            is Reach.Reachable -> return SendViaOption(platform, r, phone)
            is Reach.Unreachable -> if (unreachable == null) unreachable = r
        }
    }
    return SendViaOption(platform, unreachable ?: Reach.Unreachable(NO_NUMBER))
}

/** A [SendViaOption]'s line under its platform: "existing chat", "new chat", or why not. */
fun sendViaDetail(option: SendViaOption, person: Person): String {
    val reach = option.reach ?: return CHECKING
    val base = when (reach) {
        is Reach.Reachable -> if (reach.existing) "existing chat" else "new chat"
        is Reach.Unreachable -> return reach.reason
    }
    // Which number, when they have more than one.
    val phone = option.phone?.takeIf { person.phones.size > 1 } ?: return base
    return "$base · ${DisplayNames.number(phone)}"
}

/** A person row's line under their name. */
fun personDetail(match: RecipientMatch.PersonMatch): String? {
    match.matchedPhone?.let { return DisplayNames.number(it) }
    val person = match.person
    if (person.chats.isNotEmpty()) {
        return person.chats.map { it.platform }.distinct().sortedBy { it.ordinal }.joinToString(" · ") { it.label }
    }
    return person.phones.firstOrNull()?.let { DisplayNames.number(it) }
}

/** A group row's line under its name: "WhatsApp group". */
fun groupDetail(match: RecipientMatch.GroupMatch): String = "${match.chat.platform.label} group"

internal const val CHECKING = "checking…"
internal const val NO_NUMBER = "no number"
