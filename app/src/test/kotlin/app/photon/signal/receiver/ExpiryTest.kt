package app.photon.signal.receiver

import app.photon.signal.PlannedRow
import app.photon.signal.db.findMessageByPrefix
import app.photon.signal.db.getConversation
import app.photon.signal.db.getConversationsWithContacts
import app.photon.signal.db.getMessages
import app.photon.signal.db.insertIncomingRows
import app.photon.signal.db.insertMessage
import app.photon.signal.db.markConversationSeen
import app.photon.signal.db.markOutgoingSent
import app.photon.signal.db.setExpiresIn
import app.photon.signal.db.upsertConversation
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sweep deletes disappearing messages whose time is up, with the media
 * they downloaded or sent, and never leaves a notification showing one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ExpiryTest {

    private val local = "00000000-0000-4000-8000-00000000000a"
    private val alice = "00000000-0000-4000-8000-00000000a11c"
    private var now = 1_700_000_000_000L

    private lateinit var fx: ReceiverFixture
    private lateinit var expiry: Expiry

    @Before
    fun setUp() {
        fx = ReceiverFixture(local)
        val notifications = MessageNotifications(fx.db, fx.names, fx.notifier) { now }
        expiry = Expiry(fx.db, notifications, fx.context.filesDir) { now }
        fx.db.upsertConversation(jid = alice, name = null, isGroup = false)
    }

    @After
    fun tearDown() = fx.close()

    private fun receive(id: String, expiresIn: Int, text: String = "hi") =
        fx.db.insertIncomingRows(id, listOf(PlannedRow("text", text)), alice, alice, now / 1000, expiresIn = expiresIn, nowMs = now)

    private fun sendPhoto(id: String, file: File, expiresIn: Int) {
        fx.db.insertMessage(id, alice, local, now / 1000, "image", null, mediaUrl = file.path, isFromMe = true, status = "sending")
        fx.db.setExpiresIn(id, expiresIn)
        fx.db.markOutgoingSent(id, nowMs = now)
    }

    @Test
    fun aMessageGoesWhenItsTimeIsUpWithItsMedia() {
        val photo = File(fx.context.filesDir, "send_media_1.jpg").apply { writeBytes(ByteArray(4)) }
        sendPhoto("${local}_1_a", photo, 30)

        assertEquals(now + 30_000, expiry.sweep())
        assertTrue(photo.exists())

        now += 30_000
        assertNull(expiry.sweep())
        assertEquals(emptyList(), fx.db.getMessages(alice))
        assertFalse(photo.exists())
        // Nothing unread is left: the chat's notifications are cleared.
        assertEquals(listOf("cancel:$alice"), fx.notifier.events)
    }

    /** Only Photon's own files are deleted. */
    @Test
    fun aFileOutsidePhotonsFilesIsLeft() {
        val outside = File.createTempFile("not-photons", ".jpg")
        try {
            sendPhoto("${local}_1_a", outside, 1)
            now += 1_000
            expiry.sweep()
            assertTrue(outside.exists())
        } finally {
            outside.delete()
        }
    }

    /**
     * A read message expires while a newer one is unread: the notification
     * moves to what's still unread (its own text), without alerting again.
     */
    @Test
    fun theNotificationNeverShowsAnExpiredMessage() {
        receive("${alice}_1_a", 30, "older, unread")
        receive("${alice}_2_b", 30, "read, expiring")
        fx.db.writableDatabase.execSQL("UPDATE messages SET status = 'read', expires_at = ? WHERE id = ?", arrayOf<Any?>(now + 30_000, "${alice}_2_b"))
        fx.db.writableDatabase.execSQL("UPDATE conversations SET unread_count = 1 WHERE jid = ?", arrayOf(alice))
        fx.notifier.showing += alice

        now += 30_000
        expiry.sweep()

        assertEquals(listOf("update:$alice:Signal contact:older, unread"), fx.notifier.events)
        assertEquals(1, fx.db.getConversation(alice)?.unreadCount)
    }

    /**
     * The sweep's timer can't wake the LP3 from deep sleep, so a message can
     * outlive its time until the next sweep: the thread, the chat list and
     * reply quotes leave it out meanwhile.
     */
    @Test
    fun aMessageWhoseTimeIsUpIsHiddenBeforeTheSweep() {
        receive("${alice}_1_a", 30, "gone soon")
        fx.db.markConversationSeen(alice, nowMs = now)
        val due = now + 30_000

        assertEquals(1, fx.db.getMessages(alice, nowMs = due - 1).size)
        assertEquals(0, fx.db.getMessages(alice, nowMs = due).size)
        fx.db.writableDatabase.execSQL("UPDATE messages SET expires_at = ? WHERE id = ?", arrayOf<Any?>(System.currentTimeMillis() - 1, "${alice}_1_a"))
        assertEquals(emptyList(), fx.db.getMessages(alice))
        assertEquals(emptyList(), fx.db.getConversationsWithContacts())
        assertNull(fx.db.findMessageByPrefix("${alice}_1", liveOnly = true))
        // Still there for the sweep (and anything else that needs the row).
        assertEquals("gone soon", fx.db.findMessageByPrefix("${alice}_1")?.textBody)
    }

    @Test
    fun unreadMessagesWait() {
        receive("${alice}_1_a", 30)
        now += 3_600_000
        assertNull(expiry.sweep())
        assertEquals(1, fx.db.getMessages(alice).size)

        fx.db.markConversationSeen(alice, nowMs = now)
        assertEquals(now + 30_000, expiry.sweep())
    }
}
