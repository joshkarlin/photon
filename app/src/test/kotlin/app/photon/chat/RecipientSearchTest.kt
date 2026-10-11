package app.photon.chat

import app.photon.data.model.Conversation
import app.photon.data.model.Platform
import app.photon.data.names.DisplayNames
import app.photon.data.names.LocalContact
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * New message's matching: LP3 contacts merged with every platform's chats,
 * found by name or number. Robolectric for the framework's number parser.
 * National numbers use libphonenumber's example UK range (07400 123456).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RecipientSearchTest {
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

    private fun chat(platform: Platform, jid: String, name: String, ts: Long, phone: String?, group: Boolean = false) =
        KnownChat(platform, Conversation(jid, name, group, null, ts, 0, false, null, ts), phone)

    private val moWhatsApp = chat(Platform.WHATSAPP, "447700900123@s.whatsapp.net", "Mo Salah", 300, "+447700900123")
    private val moSms = chat(Platform.SMS, "+447700900123", "Mo Salah", 200, "+447700900123")
    private val mohammedSignal = chat(Platform.SIGNAL, "00000000-0000-4000-8000-00000000a11c", "Mo", 100, "+447400123456")
    private val ramona = chat(Platform.WHATSAPP, "447700900888@s.whatsapp.net", "Ramona", 500, "+447700900888")
    private val moeLid = chat(Platform.WHATSAPP, "112233@lid", "Moe", 50, null)
    private val bank = chat(Platform.SMS, "HSBC UK", "HSBC UK", 40, null)
    private val climbing = chat(Platform.WHATSAPP, "120363000000000001@g.us", "Monday climbing", 400, null, group = true)
    private val patWhatsApp = chat(Platform.WHATSAPP, "447700900999@s.whatsapp.net", "Pat", 30, "+447700900999")
    private val patSms = chat(Platform.SMS, "+447700900999", "+44 7700 900999", 60, "+447700900999")

    private val contacts = listOf(
        LocalContact("Mo Salah", listOf("+44 7700 900123", "020 7946 0000")),
        LocalContact("Mohammed Ali", listOf("07400 123456")),
        LocalContact("Simone Biles", listOf("+44 7700 900777")),
        LocalContact("Zoë Moreau", listOf("+44 7700 900555")),
        LocalContact("Mum", listOf("+44 7700 900456")),
    )

    // Built once the country is set.
    private val index by lazy {
        RecipientIndex(contacts, listOf(moWhatsApp, moSms, mohammedSignal, ramona, moeLid, bank, climbing, patWhatsApp, patSms))
    }

    private fun names(matches: List<RecipientMatch>) = matches.map {
        when (it) {
            is RecipientMatch.Number -> "Send to ${it.e164}"
            is RecipientMatch.PersonMatch -> it.person.name
            is RecipientMatch.GroupMatch -> "group ${it.chat.title}"
        }
    }

    private fun person(name: String) = index.people.single { it.name == name }

    // ─── People ─────────────────────────────────────────────────────

    @Test
    fun aContactTakesTheirChatsOnEveryPlatform() {
        val mo = person("Mo Salah")
        assertEquals(listOf(moWhatsApp, moSms), mo.chats)
        assertEquals(listOf("+447700900123", "+442079460000"), mo.phones)
        // Saved nationally, chatted with internationally.
        assertEquals(listOf(mohammedSignal), person("Mohammed Ali").chats)
        assertEquals(listOf("+447400123456"), person("Mohammed Ali").phones)
    }

    @Test
    fun chatsWithANumberNobodySavedAreOnePersonNamedByTheirChats() {
        val pat = person("Pat")
        assertEquals(listOf(patSms, patWhatsApp), pat.chats)
        assertEquals(listOf("+447700900999"), pat.phones)
    }

    @Test
    fun aChatWithoutANumberIsAPersonOfItsOwn() {
        assertEquals(listOf(moeLid), person("Moe").chats)
        assertEquals(emptyList(), person("HSBC UK").phones)
    }

    @Test
    fun groupsAreKeptApart() {
        assertEquals(listOf(climbing), index.groups)
    }

    // ─── Search ─────────────────────────────────────────────────────

    /** People first (where the name starts, then a later word, then anywhere; recent first), then groups. */
    @Test
    fun aNameMatchesAnywhereBestAtItsStart() {
        assertEquals(
            listOf("Mo Salah", "Mohammed Ali", "Moe", "Zoë Moreau", "Ramona", "Simone Biles", "group Monday climbing"),
            names(index.search("mo")),
        )
    }

    @Test
    fun namesMatchWhateverTheCaseOrAccents() {
        assertEquals(listOf("Zoë Moreau"), names(index.search("zoe")))
        assertEquals(listOf("Zoë Moreau"), names(index.search("ZOË M")))
        assertEquals(listOf("HSBC UK"), names(index.search("hsbc")))
    }

    @Test
    fun aGroupIsFoundByItsName() {
        assertEquals(listOf("group Monday climbing"), names(index.search("climb")))
    }

    @Test
    fun digitsMatchANumberWrittenInternationallyOrNationally() {
        val byNational = index.search("07400")
        assertEquals(listOf("Mohammed Ali"), names(byNational))
        assertEquals("+447400123456", (byNational.single() as RecipientMatch.PersonMatch).matchedPhone)
        assertEquals(listOf("Mohammed Ali"), names(index.search("+44 7400")))
        assertEquals(listOf("Mohammed Ali"), names(index.search("123 456")))
        assertEquals(emptyList(), names(index.search("74")), "too few digits")
    }

    @Test
    fun aPhoneNumberGetsASendToRowFirst() {
        assertEquals(listOf("Send to +447400123456", "Mohammed Ali"), names(index.search("07400 123456")))
        assertEquals(listOf("Send to +447700900123", "Mo Salah"), names(index.search("+44 7700 900123")))
        assertEquals(listOf("Send to +447700900321"), names(index.search("+44 7700 900321")))
        assertEquals(listOf("Mohammed Ali"), names(index.search("0740")), "not yet a whole number: no Send to")
    }

    @Test
    fun nothingTypedMatchesNothing() {
        assertEquals(emptyList(), index.search(""))
        assertEquals(emptyList(), index.search("   "))
    }

    /** Without READ_CONTACTS there are no contacts: chats are still found. */
    @Test
    fun withoutContactsChatsAreStillFound() {
        val chatsOnly = RecipientIndex(emptyList(), listOf(moWhatsApp, moSms, climbing))
        assertEquals(listOf("Mo Salah", "group Monday climbing"), names(chatsOnly.search("mo")))
        assertEquals(listOf(moWhatsApp, moSms), chatsOnly.people.single().chats)
    }

    @Test
    fun aNumberBelongsToWhoeverHasIt() {
        assertEquals(person("Mo Salah"), index.personFor("+447700900123"))
        assertEquals(person("Mohammed Ali"), index.personFor("+447400123456"))
        val stranger = index.personFor("+447700900321")
        assertEquals(Person("+44 7700 900321", listOf("+447700900321"), emptyList()), stranger)
    }

    // ─── Picking ────────────────────────────────────────────────────

    @Test
    fun theOnlyPlatformWithAChatOpensAtOnce() {
        assertNull(directChat(person("Mo Salah")), "chats on WhatsApp and SMS: Send via asks")
        assertEquals(mohammedSignal, directChat(person("Mohammed Ali")))
        assertEquals(bank, directChat(person("HSBC UK")))
        assertNull(directChat(person("Mum")), "no chat yet: Send via asks")
    }

    @Test
    fun aPlatformWithAChatOffersIt() = runBlocking<Unit> {
        val option = reachOn(Platform.SMS, person("Mo Salah")) { error("not asked") }
        assertEquals(SendViaOption(Platform.SMS, Reach.Reachable("+447700900123", existing = true)), option)
    }

    @Test
    fun otherwiseEachNumberIsTriedInTurn() = runBlocking<Unit> {
        val mo = person("Mo Salah")
        val asked = mutableListOf<String>()
        val option = reachOn(Platform.SIGNAL, mo) { phone ->
            asked += phone
            if (phone == "+442079460000") Reach.Reachable("aci", existing = false) else Reach.Unreachable("not known to Signal")
        }
        assertEquals(listOf("+447700900123", "+442079460000"), asked)
        assertEquals(SendViaOption(Platform.SIGNAL, Reach.Reachable("aci", existing = false), "+442079460000"), option)
        assertEquals("new chat · +44 20 7946 0000", sendViaDetail(option, mo))

        val none = reachOn(Platform.SIGNAL, mo) { Reach.Unreachable(if (it.startsWith("+")) "not known to Signal" else "other") }
        assertEquals("not known to Signal", sendViaDetail(none, mo), "the first number's reason")
    }

    @Test
    fun aPersonWithoutANumberIsReachedOnlyThroughTheirChat() = runBlocking<Unit> {
        val option = reachOn(Platform.SIGNAL, person("Moe")) { error("not asked") }
        assertEquals(SendViaOption(Platform.SIGNAL, Reach.Unreachable("no number")), option)
    }

    @Test
    fun rowDetails() {
        val mum = person("Mum")
        assertEquals("checking…", sendViaDetail(SendViaOption(Platform.WHATSAPP, null), mum))
        assertEquals("existing chat", sendViaDetail(SendViaOption(Platform.SMS, Reach.Reachable("x", true), "+447700900456"), mum))
        assertEquals("new chat", sendViaDetail(SendViaOption(Platform.SMS, Reach.Reachable("x", false), "+447700900456"), mum))
        assertEquals("WhatsApp disconnected", sendViaDetail(SendViaOption(Platform.WHATSAPP, Reach.Unreachable("WhatsApp disconnected")), mum))

        assertEquals("WhatsApp · SMS", personDetail(RecipientMatch.PersonMatch(person("Mo Salah"))))
        assertEquals("+44 7700 900456", personDetail(RecipientMatch.PersonMatch(mum)))
        assertEquals("+44 7400 123456", personDetail(RecipientMatch.PersonMatch(person("Mohammed Ali"), "+447400123456")))
        assertNull(personDetail(RecipientMatch.PersonMatch(Person("Nobody", emptyList(), emptyList()))))
        assertEquals("WhatsApp group", groupDetail(RecipientMatch.GroupMatch(climbing)))
    }
}
