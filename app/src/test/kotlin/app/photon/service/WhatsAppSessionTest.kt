package app.photon.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.photon.data.db.createWhatsAppSchema
import app.photon.data.db.writeWhatsAppDb
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Starting, stopping and resetting WhatsApp, on fake bridge processes. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class WhatsAppSessionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dbFile = File(context.filesDir, "messages.db")

    /** For each bridge launched: whether the account's files were still there when it was told to stop. */
    private val launched = CopyOnWriteArrayList<FakeProcess>()
    private val filesAtStop = CopyOnWriteArrayList<Boolean>()

    private val session = WhatsAppSession(
        context, scope,
        GoBridge(
            {
                FakeProcess(hangsOnExit = true) {
                    filesAtStop += File(context.filesDir, "whatsmeow.db").exists()
                }.also { launched += it }
            },
            exitGraceMs = 200,
        ),
    )

    @After
    fun tearDown() {
        runBlocking { session.stop() }
        scope.cancel()
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private suspend fun awaitConnected() = withTimeout(5_000) { session.bridge.isConnected.first { it } }

    private fun writeChat(jid: String) = writeWhatsAppDb(dbFile) { db ->
        db.execSQL("INSERT INTO conversations (jid, name, last_timestamp) VALUES (?, 'Chat', 1)", arrayOf(jid))
        db.execSQL(
            "INSERT INTO messages (id, conversation_jid, sender_jid, timestamp, content_type, text_body) " +
                "VALUES (?, ?, ?, 1, 'text', 'hi')",
            arrayOf("m-$jid", jid, jid),
        )
    }

    @Test
    fun startingTwiceRunsOneBridge() = runBlocking {
        session.start()
        session.start()
        awaitConnected()
        delay(300)
        assertEquals(1, launched.size)
    }

    @Test
    fun stoppingEndsTheBridgeAndNothingRestartsIt() = runBlocking {
        session.start()
        awaitConnected()
        session.stop()
        session.stop()

        assertFalse(launched.single().isAlive)
        assertFalse(session.bridge.isConnected.value)
        // Past the supervisor's first restart delay (1s).
        delay(1_500)
        assertEquals(1, launched.size, "the stopped bridge was restarted")
    }

    @Test
    fun resetWipesTheAccountWithTheOldBridgeStoppedThenStartsAnother() = runBlocking {
        writeWhatsAppDb(dbFile, ::createWhatsAppSchema)
        writeChat("old@s.whatsapp.net")
        File(context.filesDir, "whatsmeow.db").writeText("old session")
        File(context.filesDir, "media").apply { mkdirs() }.resolve("photo.jpg").writeText("x")
        session.start()
        awaitConnected()
        assertEquals(listOf("old@s.whatsapp.net"), session.database.getConversations().map { it.jid })
        session.cacheQrCode("old qr")

        session.reset()

        val old = launched.first()
        assertEquals(listOf(true), filesAtStop.take(1), "the bridge was stopped before the wipe")
        assertFalse(old.isAlive)
        assertTrue(old.killed, "a bridge stuck on exit is killed before the wipe")
        assertFalse(File(context.filesDir, "whatsmeow.db").exists())
        assertFalse(dbFile.exists())
        assertFalse(File(context.filesDir, "media").exists())
        assertEquals(null, session.cachedQrCode())
        // A new bridge for the next pairing.
        awaitConnected()
        assertEquals(2, launched.size)
        assertTrue(launched.last().isAlive)

        // The new account's database is read, not the deleted file the
        // reader still had open.
        writeWhatsAppDb(dbFile, ::createWhatsAppSchema)
        writeChat("new@s.whatsapp.net")
        assertEquals(listOf("new@s.whatsapp.net"), session.database.getConversations().map { it.jid })
    }

    @Test
    fun resettingTwiceLeavesOneRunningBridge() = runBlocking {
        session.start()
        awaitConnected()
        session.reset()
        session.reset()
        awaitConnected()
        delay(300)
        assertEquals(1, launched.count { it.isAlive })
    }
}
