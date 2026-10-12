package app.photon.service

import app.photon.data.model.BridgeEvent
import app.photon.data.names.DisplayNames
import app.photon.data.repository.WhatsAppNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * When messages disappear, the chat's notification must not keep showing
 * one: it goes, or shows the newest message left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RemovedNotificationTest {

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

    // As the bridge writes it.
    private fun removed(json: String) = BridgeEvent(type = "messages_removed", payload = Json.parseToJsonElement(json).jsonObject)

    @Test
    fun nothingUnreadWentKeepsIt() {
        val evt = removed("""{"conversation_jid":"a@s.whatsapp.net","message_ids":["m1"],"reason":"expired","was_unread":false,"unread_count":2}""")
        assertEquals(RemovedNotification.Keep, removedNotification(evt))
    }

    @Test
    fun theLastUnreadGoingCancelsIt() {
        val evt = removed("""{"conversation_jid":"a@s.whatsapp.net","message_ids":["m1"],"reason":"expired","was_unread":true,"unread_count":0}""")
        assertEquals(RemovedNotification.Cancel, removedNotification(evt))
    }

    @Test
    fun anOlderUnreadGoingKeepsIt() {
        val evt = removed("""{"conversation_jid":"a@s.whatsapp.net","message_ids":["m1"],"reason":"expired","was_unread":true,"unread_count":1,"newest_removed":false}""")
        assertEquals(RemovedNotification.Keep, removedNotification(evt))
    }

    /** The count can include a call or "mark as unread", which leave no message to show. */
    @Test
    fun theShownMessageGoingWithNothingLeftToShowCancelsIt() {
        val evt = removed("""{"conversation_jid":"a@s.whatsapp.net","message_ids":["m1"],"reason":"expired","was_unread":true,"unread_count":1,"newest_removed":true}""")
        assertEquals(RemovedNotification.Cancel, removedNotification(evt))
    }

    @Test
    fun theShownMessageGoingShowsTheNewestLeft() {
        val evt = removed(
            """{"conversation_jid":"120363000000000000@g.us","message_ids":["m2"],"reason":"expired","was_unread":true,"unread_count":1,"newest_removed":true,
            "newest_unread":{"conversation_jid":"120363000000000000@g.us","message_id":"m1","is_group":true,
            "conversation_name":"Climbing","sender_jid":"447700900456@s.whatsapp.net","sender_name":"Al",
            "text_body":"see you at 8","content_type":"text","is_from_me":false}}""",
        )
        val show = assertIs<RemovedNotification.Show>(removedNotification(evt))
        val names = WhatsAppNames(mapOf("+447700900456" to "Alice")::get)
        assertEquals(MessageNotificationText("Climbing", "Alice: see you at 8"), names.messageNotification(show.newest, null))
    }
}
