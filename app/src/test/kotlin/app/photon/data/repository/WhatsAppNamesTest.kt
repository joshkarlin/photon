package app.photon.data.repository

import app.photon.data.model.BridgeEvent
import app.photon.data.model.Conversation
import app.photon.data.model.Participant
import app.photon.data.names.DisplayNames
import app.photon.service.MessageNotificationText
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The LP3 overlay on WhatsApp's names, in chats, groups, notifications and calls. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class WhatsAppNamesTest {

    private val mum = "447700900123@s.whatsapp.net"
    private val alice = "447700900456@s.whatsapp.net"
    private val stranger = "447700900999@s.whatsapp.net"
    private val lid = "123456789012345@lid"
    private val group = "120363000000000000@g.us"
    private val names = WhatsAppNames(
        mapOf("+447700900123" to "Mum", "+447700900456" to "Alice")::get,
    )
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

    private fun event(type: String, vararg fields: Pair<String, Any>) = BridgeEvent(
        type = type,
        payload = JsonObject(
            fields.associate { (k, v) ->
                k to when (v) {
                    is Boolean -> JsonPrimitive(v)
                    else -> JsonPrimitive(v.toString())
                }
            },
        ),
    )

    @Test
    fun theLp3ContactWinsOverWhatTheBridgeStored() {
        assertEquals("Mum", names.resolve(conv(mum, "Jane Smith")).name)
        assertEquals("Jane Smith", names.resolve(conv(stranger, "Jane Smith")).name)
        assertEquals("+44 7700 900999", names.resolve(conv(stranger, "")).name)
    }

    /** No number for an @lid-only person, so no LP3 tier, and never their LID digits. */
    @Test
    fun lidOnlyPeopleSkipTheLp3Tier() {
        assertEquals("Bob", names.resolve(conv(lid, "Bob")).name)
        assertEquals("WhatsApp contact", names.resolve(conv(lid, null)).name)
    }

    @Test
    fun groupsKeepTheirSubject() {
        assertEquals("Climbing", names.resolve(conv(group, "Climbing", isGroup = true)).name)
        assertEquals("WhatsApp group", names.resolve(conv(group, null, isGroup = true)).name)
    }

    @Test
    fun senderNamesCoverMembersAndAnyoneElseWhoWrote() {
        val got = names.participantNames(
            listOf(Participant(group, alice, "Ali", "member"), Participant(group, lid, "Bob", "member")),
            senders = listOf(alice, stranger),
        )
        assertEquals(
            mapOf(alice to "Alice", lid to "Bob", stranger to "+44 7700 900999"),
            got,
        )
    }

    @Test
    fun aGroupNotificationIsTitledWithTheGroupAndNamesTheSender() {
        val evt = event(
            "new_message", "conversation_jid" to group, "is_group" to true, "conversation_name" to "Climbing",
            "sender_jid" to alice, "sender_name" to "Ali", "text_body" to "see you at 8",
        )
        assertEquals(MessageNotificationText("Climbing", "Alice: see you at 8"), names.messageNotification(evt, null))

        val fromLid = event(
            "new_message", "conversation_jid" to group, "is_group" to true, "conversation_name" to "Climbing",
            "sender_jid" to lid, "sender_name" to "", "content_type" to "image",
        )
        assertEquals(
            MessageNotificationText("Climbing", "WhatsApp contact: [ image ]"),
            names.messageNotification(fromLid, null),
        )
    }

    @Test
    fun aDmNotificationIsTitledWithThePerson() {
        val evt = event(
            "new_message", "conversation_jid" to mum, "is_group" to false, "conversation_name" to "Jane",
            "sender_jid" to mum, "sender_name" to "Jane", "text_body" to "hi",
        )
        assertEquals(MessageNotificationText("Mum", "hi"), names.messageNotification(evt, null))
    }

    /** Bridges before the naming fields: a group's sender_name was the group's name. */
    @Test
    fun olderBridgeEventsStillNameTheChat() {
        val group = event("new_message", "conversation_jid" to group, "sender_name" to "Climbing", "text_body" to "hi")
        assertEquals(
            MessageNotificationText("Climbing", "hi"),
            names.messageNotification(group, conv(this.group, "Climbing", isGroup = true)),
        )
        val dm = event("new_message", "conversation_jid" to stranger, "sender_name" to "Jane", "text_body" to "hi")
        assertEquals(MessageNotificationText("Jane", "hi"), names.messageNotification(dm, null))
        val unnamed = event("new_message", "conversation_jid" to stranger, "text_body" to "hi")
        assertEquals(MessageNotificationText("+44 7700 900999", "hi"), names.messageNotification(unnamed, null))
    }

    @Test
    fun callsNameTheCallerOrTheGroup() {
        assertEquals(
            "Mum",
            names.callerTitle(event("call_incoming", "conversation_jid" to mum, "caller_jid" to mum, "caller_name" to "Jane"), null),
        )
        assertEquals(
            "Book club",
            names.callerTitle(
                event("call_incoming", "conversation_jid" to group, "caller_jid" to alice, "caller_name" to "Book club", "is_group" to true),
                null,
            ),
        )
        // Older bridges: the caller's number, or a placeholder, as caller_name.
        assertEquals("Mum", names.callerTitle(event("call_missed", "conversation_jid" to mum, "caller_name" to "+447700900123"), null))
        assertEquals(
            "WhatsApp contact",
            names.callerTitle(event("call_missed", "conversation_jid" to lid, "caller_name" to "WhatsApp contact"), null),
        )
    }
}
