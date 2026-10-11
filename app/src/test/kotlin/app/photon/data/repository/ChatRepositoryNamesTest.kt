package app.photon.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.photon.data.db.PhotonDatabase
import app.photon.data.model.Conversation
import app.photon.data.names.DisplayNames
import app.photon.data.names.LocalContacts
import app.photon.service.BridgeClient
import app.photon.service.GoBridge
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.Pipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * WhatsApp names re-resolve when either source changes: an LP3 contact saved
 * or edited, or the bridge's names_changed after it learns a WhatsApp name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ChatRepositoryNamesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val book = mutableMapOf<String, String>()
    private val contacts = LocalContacts(context) { book[it] }
    private val dbFile = File(context.filesDir, "messages.db")
    private lateinit var bridgeOut: BufferedWriter
    private lateinit var repo: ChatRepository
    private var savedCountry = ""

    private val dad = "447700900999@s.whatsapp.net"
    private val lid = "123456789012345@lid"
    private val group = "120363000000000000@g.us"

    @Before
    fun setUp() {
        savedCountry = DisplayNames.defaultCountry
        DisplayNames.defaultCountry = "GB"
        write { db ->
            db.execSQL(
                "CREATE TABLE conversations (jid TEXT PRIMARY KEY, name TEXT, is_group INTEGER NOT NULL DEFAULT 0, " +
                    "last_message_id TEXT, last_timestamp INTEGER NOT NULL DEFAULT 0, unread_count INTEGER NOT NULL DEFAULT 0, " +
                    "is_muted INTEGER NOT NULL DEFAULT 0, avatar_url TEXT, updated_at INTEGER NOT NULL DEFAULT 0, " +
                    "muted_until INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL(
                "CREATE TABLE messages (id TEXT PRIMARY KEY, conversation_jid TEXT NOT NULL, sender_jid TEXT NOT NULL, " +
                    "timestamp INTEGER NOT NULL, content_type TEXT NOT NULL, text_body TEXT, media_url TEXT, media_mime TEXT, " +
                    "media_size INTEGER, thumbnail_path TEXT, sticker_pack_id TEXT, reply_to_id TEXT, " +
                    "edit_version INTEGER NOT NULL DEFAULT 0, is_from_me INTEGER NOT NULL DEFAULT 0, " +
                    "status TEXT NOT NULL DEFAULT 'sent', raw_proto BLOB)",
            )
            db.execSQL(
                "CREATE TABLE participants (conversation_jid TEXT NOT NULL, jid TEXT NOT NULL, display_name TEXT, " +
                    "role TEXT DEFAULT 'member', PRIMARY KEY (conversation_jid, jid))",
            )
            db.execSQL("INSERT INTO conversations (jid, name, is_group, last_timestamp) VALUES (?, '', 0, 3)", arrayOf(dad))
            db.execSQL("INSERT INTO conversations (jid, name, is_group, last_timestamp) VALUES (?, '', 0, 2)", arrayOf(lid))
            db.execSQL("INSERT INTO conversations (jid, name, is_group, last_timestamp) VALUES (?, 'Climbing', 1, 1)", arrayOf(group))
            for ((i, row) in listOf(dad to dad, lid to lid, group to dad).withIndex()) {
                db.execSQL(
                    "INSERT INTO messages (id, conversation_jid, sender_jid, timestamp, content_type, text_body) " +
                        "VALUES (?, ?, ?, ?, 'text', 'hi')",
                    arrayOf<Any>("m$i", row.first, row.second, i),
                )
            }
        }

        val client = BridgeClient(GoBridge({ null }))
        val toClient = Pipe.open()
        val toBridge = Pipe.open()
        bridgeOut = Channels.newOutputStream(toClient.sink()).bufferedWriter()
        client.attach(Channels.newInputStream(toClient.source()), Channels.newOutputStream(toBridge.sink()))
        repo = ChatRepository(PhotonDatabase(context), client, scope, contacts)
    }

    @After
    fun tearDown() {
        try { bridgeOut.close() } catch (_: IOException) {}
        scope.cancel()
        DisplayNames.defaultCountry = savedCountry
        dbFile.delete()
    }

    // WAL, as the bridge writes it.
    private fun write(block: (SQLiteDatabase) -> Unit) =
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.enableWriteAheadLogging()
            block(db)
        }

    private fun bridgePrints(line: String) {
        bridgeOut.write(line)
        bridgeOut.write("\n")
        bridgeOut.flush()
    }

    private fun names(list: List<Conversation>) = list.associate { it.jid to it.name }

    private fun <T> awaitValue(flow: Flow<T>, until: (T) -> Boolean): T =
        runBlocking { withTimeout(5_000) { flow.first(until) } }

    @Test
    fun chatNamesFollowTheLp3AddressBookAndTheBridge() {
        val first = awaitValue(repo.conversations.filterNotNull()) { it.isNotEmpty() }
        assertEquals(
            mapOf(dad to "+44 7700 900999", lid to "WhatsApp contact", group to "Climbing"),
            names(first),
        )

        // Saved on the LP3.
        book["+447700900999"] = "Dad"
        contacts.invalidate()
        awaitValue(repo.conversations.filterNotNull()) { names(it)[dad] == "Dad" }

        // The bridge learned the @lid person's push name.
        write { it.execSQL("UPDATE conversations SET name = 'Bob' WHERE jid = ?", arrayOf(lid)) }
        bridgePrints("""{"type":"names_changed","payload":{"jids":["$lid"]}}""")
        awaitValue(repo.conversations.filterNotNull()) { names(it)[lid] == "Bob" }
    }

    @Test
    fun groupSenderNamesFollowTheLp3AddressBook() {
        val emitted = Channel<Map<String, String>>(Channel.UNLIMITED)
        scope.launch { repo.participantNames(group).collect { emitted.send(it) } }
        assertEquals(mapOf(dad to "+44 7700 900999"), runBlocking { withTimeout(5_000) { emitted.receive() } })

        book["+447700900999"] = "Dad"
        contacts.invalidate()
        assertEquals(mapOf(dad to "Dad"), runBlocking { withTimeout(5_000) { emitted.receive() } })
    }
}
