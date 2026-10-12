package app.photon.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.photon.data.db.createWhatsAppSchema
import app.photon.data.db.writeWhatsAppDb
import app.photon.data.model.Platform
import app.photon.data.names.DisplayNames
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bridge's events that correct a chat's notification: a placeholder
 * replaced by its message, and messages that disappeared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class WhatsAppNotificationEventsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chat = "447700900001@s.whatsapp.net"
    private val launched = CopyOnWriteArrayList<FakeProcess>()
    private val session = WhatsAppSession(
        context, scope,
        GoBridge({ FakeProcess().also { launched += it } }, exitGraceMs = 200),
        logoutTimeoutMs = 300,
    )
    private var savedCountry = ""

    @Before
    fun setUp() {
        savedCountry = DisplayNames.defaultCountry
        DisplayNames.defaultCountry = "GB"
        writeWhatsAppDb(File(context.filesDir, "messages.db")) { db ->
            createWhatsAppSchema(db)
            db.execSQL("INSERT INTO conversations (jid, name, last_timestamp, unread_count) VALUES (?, 'Alice', 1, 1)", arrayOf(chat))
        }
        session.start()
        runBlocking { withTimeout(5_000) { session.bridge.isConnected.first { it } } }
    }

    @After
    fun tearDown() {
        runBlocking { session.stop() }
        scope.cancel()
        DisplayNames.defaultCountry = savedCountry
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun notifications(): List<Notification> =
        shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    private fun awaitNotifications(until: (List<Notification>) -> Boolean): List<Notification> {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            notifications().takeIf(until)?.let { return it }
            Thread.sleep(20)
        }
        return notifications().also { assertTrue(until(it), "notifications: ${it.map { n -> n.text }}") }
    }

    private val Notification.text get() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private val Notification.alertsOnlyOnce get() = flags and Notification.FLAG_ONLY_ALERT_ONCE != 0

    private fun bridgeSays(line: String) = launched.single().print(line)

    // new_message's fields, as the bridge writes them.
    private fun newMessage(id: String, text: String) =
        """"conversation_jid":"$chat","message_id":"$id","is_group":false,"conversation_name":"Alice",""" +
            """"sender_jid":"$chat","sender_name":"Alice","text_body":"$text","content_type":"text","is_from_me":false"""

    /** Archiving doesn't silence a chat, as in WhatsApp; muting does. */
    @Test
    fun archivedChatsStillNotify() {
        writeWhatsAppDb(File(context.filesDir, "messages.db")) { db ->
            db.execSQL("UPDATE conversations SET is_archived = 1 WHERE jid = ?", arrayOf(chat))
        }
        bridgeSays("""{"type":"new_message","payload":{${newMessage("m1", "hello")}}}""")
        awaitNotifications { it.singleOrNull()?.text == "hello" }
    }

    @Test
    fun aReplacedPlaceholderUpdatesTheNotificationQuietly() {
        NotificationHelper.showMessageNotification(context, "Alice", "[ unavailable ]", Platform.WHATSAPP, chat)
        bridgeSays("""{"type":"message_replaced","payload":{${newMessage("u1", "here it is")},"newest_unread":true}}""")

        val shown = awaitNotifications { it.singleOrNull()?.text == "here it is" }.single()
        assertTrue(shown.alertsOnlyOnce, "the message was already announced")
    }

    @Test
    fun aReplacedPlaceholderThatIsNoLongerNewestLeavesItAlone() {
        NotificationHelper.showMessageNotification(context, "Alice", "newer", Platform.WHATSAPP, chat)
        bridgeSays("""{"type":"message_replaced","payload":{${newMessage("u1", "older")},"newest_unread":false}}""")
        // Then something it does act on, to know the first was handled.
        bridgeSays("""{"type":"messages_removed","payload":{"conversation_jid":"$chat","message_ids":["x"],"reason":"expired","was_unread":false,"unread_count":1}}""")
        Thread.sleep(300)
        assertEquals(listOf("newer"), notifications().map { it.text })
    }

    /** A notification the user dismissed isn't brought back to be corrected. */
    @Test
    fun aDismissedNotificationStaysDismissed() {
        bridgeSays("""{"type":"message_replaced","payload":{${newMessage("u1", "here it is")},"newest_unread":true}}""")
        bridgeSays(
            """{"type":"messages_removed","payload":{"conversation_jid":"$chat","message_ids":["m2"],"reason":"expired",""" +
                """"was_unread":true,"unread_count":1,"newest_removed":true,"newest_unread":{${newMessage("m1", "older")}}}}""",
        )
        Thread.sleep(300)
        assertEquals(emptyList(), notifications().map { it.text })
    }

    @Test
    fun theShownMessageDisappearingShowsTheNewestLeft() {
        NotificationHelper.showMessageNotification(context, "Alice", "secret", Platform.WHATSAPP, chat)
        bridgeSays(
            """{"type":"messages_removed","payload":{"conversation_jid":"$chat","message_ids":["m2"],"reason":"expired",""" +
                """"was_unread":true,"unread_count":1,"newest_removed":true,"newest_unread":{${newMessage("m1", "older")}}}}""",
        )
        val shown = awaitNotifications { it.singleOrNull()?.text == "older" }.single()
        assertTrue(shown.alertsOnlyOnce)
    }

    @Test
    fun theLastUnreadDisappearingCancelsIt() {
        NotificationHelper.showMessageNotification(context, "Alice", "secret", Platform.WHATSAPP, chat)
        bridgeSays("""{"type":"messages_removed","payload":{"conversation_jid":"$chat","message_ids":["m1"],"reason":"expired","was_unread":true,"unread_count":0}}""")
        awaitNotifications { it.isEmpty() }
    }
}
