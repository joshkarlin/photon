package app.photon.service

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.upsertConversation
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Starting, stopping and resetting Signal while it isn't linked: no network needed. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SignalSessionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = SignalSession(context, scope)

    @After
    fun tearDown() {
        session.stop()
        scope.cancel()
    }

    @Test
    fun anUnlinkedSessionStartsAndStopsWithoutConnecting() {
        session.start()
        session.start()
        assertEquals("disconnected", session.receiver.state.value)
        session.stop()
        session.stop()
        assertEquals("disconnected", session.receiver.state.value)
    }

    /**
     * The expiry sweep's wait stops in deep sleep; the screen turning on
     * wakes it, so a message whose time came while the LP3 slept goes at once.
     */
    @Test
    fun theScreenTurningOnRunsTheExpirySweep() {
        val written = SignalMessageDatabase(context)
        fun expiredRow(id: String) {
            written.upsertConversation(jid = "aci", name = null, isGroup = false)
            written.writableDatabase.execSQL(
                "INSERT INTO messages (id, conversation_jid, sender_jid, timestamp, content_type, text_body, is_from_me, " +
                    "status, expires_in, expires_at) VALUES (?, 'aci', 'aci', 1, 'text', 'x', 0, 'read', 30, ?)",
                arrayOf<Any?>(id, System.currentTimeMillis() - 1_000),
            )
        }
        fun gone(id: String) = written.readableDatabase.rawQuery("SELECT 1 FROM messages WHERE id = ?", arrayOf(id)).use { !it.moveToFirst() }
        fun waitFor(what: String, check: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 5_000
            while (!check()) {
                if (System.currentTimeMillis() > deadline) error("timed out waiting for $what")
                Thread.sleep(20)
            }
        }
        try {
            // The first sweep, as the session starts.
            expiredRow("aci_1_a")
            session.start()
            waitFor("the first sweep") { gone("aci_1_a") }

            // Written through another handle, so nothing wakes the sweep but the screen.
            expiredRow("aci_2_b")
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
            shadowOf(Looper.getMainLooper()).idle()
            waitFor("the sweep the screen woke") { gone("aci_2_b") }
        } finally {
            written.close()
        }
    }

    @Test
    fun resetWipesTheAccountAndLeavesEverythingUsableForTheNextLink() {
        session.credentials.aciString = "11111111-1111-1111-1111-111111111111"
        // Written as the receiver would, through another handle on the same file.
        SignalMessageDatabase(context).use { it.upsertConversation(jid = "old", name = "Old chat", isGroup = false) }
        assertNotNull(session.repository.getConversation("old"))
        File(context.filesDir, "signal_media").apply { mkdirs() }.resolve("a.jpg").writeText("x")

        // Not registered, so the logout makes no server request.
        session.reset()
        session.reset()

        assertNull(session.credentials.aciString)
        assertFalse(context.getDatabasePath(SignalMessageDatabase.DATABASE_NAME).exists())
        assertFalse(File(context.filesDir, "signal_media").exists())

        // The next link reads and writes through the same components, on fresh files.
        assertNull(session.repository.getConversation("old"))
        session.credentials.aciString = "22222222-2222-2222-2222-222222222222"
        assertEquals("22222222-2222-2222-2222-222222222222", session.credentials.aciString)
        session.start()
        assertEquals("disconnected", session.receiver.state.value)
    }
}
