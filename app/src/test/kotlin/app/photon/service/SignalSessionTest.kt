package app.photon.service

import android.content.Context
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
