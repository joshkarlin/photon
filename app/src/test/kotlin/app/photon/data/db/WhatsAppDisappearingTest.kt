package app.photon.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Disappearing messages as the app reads them: each chat's timer, and
 * messages past their expiry hidden until the bridge deletes them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class WhatsAppDisappearingTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbFile = File(context.filesDir, "messages.db")
    private val now = System.currentTimeMillis() / 1000
    private val chat = "447700900001@s.whatsapp.net"
    private val gone = "447700900002@s.whatsapp.net"

    @Before
    fun setUp() {
        dbFile.delete()
        writeWhatsAppDb(dbFile) { db ->
            createWhatsAppSchema(db)
            db.execSQL(
                "INSERT INTO conversations (jid, last_timestamp, disappearing_seconds, disappearing_set_at) VALUES (?, ?, 604800, 1)",
                arrayOf<Any>(chat, now),
            )
            db.execSQL("INSERT INTO conversations (jid, last_timestamp) VALUES (?, ?)", arrayOf<Any>(gone, now))
            message(db, "kept", chat, expiresAt = 0)
            message(db, "later", chat, expiresAt = now + 3600)
            message(db, "expired", chat, expiresAt = now - 1)
            message(db, "line", chat, expiresAt = 0, contentType = "system", sender = "")
            message(db, "alsoExpired", gone, expiresAt = now - 1)
        }
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    private fun message(
        db: SQLiteDatabase, id: String, conv: String, expiresAt: Long,
        contentType: String = "text", sender: String = conv,
    ) = db.execSQL(
        "INSERT INTO messages (id, conversation_jid, sender_jid, timestamp, content_type, text_body, expires_at) " +
            "VALUES (?, ?, ?, ?, ?, 'x', ?)",
        arrayOf<Any>(id, conv, sender, now - 10, contentType, expiresAt),
    )

    @Test
    fun aChatCarriesItsTimer() {
        val db = PhotonDatabase(context)
        assertEquals(604800L, db.getConversation(chat)?.disappearingSeconds)
        assertEquals(0L, db.getConversation(gone)?.disappearingSeconds)
    }

    @Test
    fun messagesPastTheirExpiryAreHidden() {
        val db = PhotonDatabase(context)
        assertEquals(setOf("kept", "later", "line"), db.getMessages(chat).map { it.id }.toSet())
        assertNull(db.getMessage("expired"), "a reply must not quote a message that has disappeared")
        assertEquals(listOf(chat), db.getConversations().map { it.jid }, "a chat whose messages all disappeared isn't listed")
    }

    @Test
    fun timerLinesHaveNoSender() {
        assertEquals(listOf(chat), PhotonDatabase(context).getMessageSenders(chat))
    }

    /** Moments after an upgrade, before the bridge has migrated, the old schema still reads. */
    @Test
    fun aDatabaseWithoutTheColumnsStillReads() {
        dbFile.delete()
        writeWhatsAppDb(dbFile) { db ->
            db.execSQL(
                "CREATE TABLE conversations (jid TEXT PRIMARY KEY, name TEXT, is_group INTEGER NOT NULL DEFAULT 0, " +
                    "last_message_id TEXT, last_timestamp INTEGER NOT NULL DEFAULT 0, unread_count INTEGER NOT NULL DEFAULT 0, " +
                    "is_muted INTEGER NOT NULL DEFAULT 0, avatar_url TEXT, updated_at INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL(
                "CREATE TABLE messages (id TEXT PRIMARY KEY, conversation_jid TEXT NOT NULL, sender_jid TEXT NOT NULL, " +
                    "timestamp INTEGER NOT NULL, content_type TEXT NOT NULL, text_body TEXT, media_url TEXT, media_mime TEXT, " +
                    "media_size INTEGER, thumbnail_path TEXT, sticker_pack_id TEXT, reply_to_id TEXT, " +
                    "edit_version INTEGER NOT NULL DEFAULT 0, is_from_me INTEGER NOT NULL DEFAULT 0, " +
                    "status TEXT NOT NULL DEFAULT 'sent', raw_proto BLOB)",
            )
            db.execSQL("INSERT INTO conversations (jid, last_timestamp) VALUES (?, 1)", arrayOf(chat))
            db.execSQL(
                "INSERT INTO messages (id, conversation_jid, sender_jid, timestamp, content_type) VALUES ('m', ?, ?, 1, 'text')",
                arrayOf(chat, chat),
            )
        }
        val db = PhotonDatabase(context)
        assertEquals(listOf("m"), db.getMessages(chat).map { it.id })
        assertEquals(0L, db.getConversation(chat)?.disappearingSeconds)
        assertEquals(listOf(chat), db.getConversations().map { it.jid })
    }
}
