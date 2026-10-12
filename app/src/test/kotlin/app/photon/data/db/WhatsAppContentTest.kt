package app.photon.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.photon.data.model.ContentTypes
import app.photon.data.model.Message
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
 * WhatsApp rows as the bridge writes them for 0.5.0's content types and
 * chat state, read as the UI gets them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class WhatsAppContentTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbFile = File(context.filesDir, "messages.db")
    private val chat = "447700900001@s.whatsapp.net"
    private val archived = "447700900002@s.whatsapp.net"

    @Before
    fun setUp() {
        dbFile.delete()
        writeWhatsAppDb(dbFile) { db ->
            createWhatsAppSchema(db)
            db.execSQL("INSERT INTO conversations (jid, last_timestamp) VALUES (?, 1)", arrayOf(chat))
            db.execSQL("INSERT INTO conversations (jid, last_timestamp, is_archived, archived_at) VALUES (?, 1, 1, 5)", arrayOf(archived))
            for ((id, contentType, text, mediaUrl, mime) in listOf(
                Row("loc", ContentTypes.LOCATION, "British Museum", "geo:51.5194,-0.127", null),
                Row("pin", ContentTypes.LOCATION, null, "geo:1.5,2.25", null),
                Row("doc", "document", "Budget 2027.xlsx", null, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                Row("vo", ContentTypes.VIEW_ONCE, "photo", null, null),
                Row("un", ContentTypes.UNAVAILABLE, null, null, null),
            )) {
                db.execSQL(
                    "INSERT INTO messages (id, conversation_jid, sender_jid, timestamp, content_type, text_body, media_url, media_mime) " +
                        "VALUES (?, ?, ?, 1, ?, ?, ?, ?)",
                    arrayOf(id, chat, chat, contentType, text, mediaUrl, mime),
                )
            }
            db.execSQL(
                "INSERT INTO messages (id, conversation_jid, sender_jid, timestamp, content_type, text_body, status) " +
                    "VALUES ('line', ?, '', 1, ?, 'Disappearing messages · 7 days', 'read')",
                arrayOf(chat, ContentTypes.SYSTEM),
            )
            db.execSQL(
                "INSERT INTO messages (id, conversation_jid, sender_jid, timestamp, content_type, text_body) VALUES ('a', ?, ?, 1, 'text', 'hi')",
                arrayOf(archived, archived),
            )
        }
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    private data class Row(val id: String, val type: String, val text: String?, val mediaUrl: String?, val mime: String?)

    private fun message(id: String): Message = PhotonDatabase(context).getMessage(id)!!

    @Test
    fun placesCarryTheirLabelAndMapLink() {
        assertEquals("British Museum", message("loc").textBody)
        assertEquals("geo:51.5194,-0.127", message("loc").mediaUrl)
        assertNull(message("pin").textBody)
        assertEquals("geo:1.5,2.25", message("pin").mediaUrl)
    }

    /** What Save needs to save a document under its own name. */
    @Test
    fun documentsCarryTheirNameAndType() {
        assertEquals("Budget 2027.xlsx", message("doc").textBody)
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", message("doc").mediaMime)
    }

    @Test
    fun placeholdersAndLines() {
        assertEquals(ContentTypes.VIEW_ONCE to "photo", message("vo").let { it.contentType to it.textBody })
        assertEquals(ContentTypes.UNAVAILABLE, message("un").contentType)
        assertEquals("Disappearing messages · 7 days", message("line").textBody)
    }

    @Test
    fun archivedChatsAreMarked() {
        val db = PhotonDatabase(context)
        assertTrue(db.getConversation(archived)!!.isArchived)
        assertFalse(db.getConversation(chat)!!.isArchived)
        assertEquals(setOf(chat, archived), db.getConversations().map { it.jid }.toSet(), "the UI filters archived chats")
    }
}
