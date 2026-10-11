package app.photon.signal.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.photon.signal.store.SignalProtocolDatabase
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Signal reset deletes the databases while the app keeps running. The same
 * helper instances must then work against fresh, empty files — deleting the
 * files under an open handle kept serving (and writing) the old data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SignalResetTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun messageDatabaseIsEmptyAndUsableAfterCloseAndDelete() {
        val db = SignalMessageDatabase(context)
        db.upsertConversation(jid = "old", name = "Old chat", isGroup = false)

        db.closeAndDelete(context)

        assertFalse(context.getDatabasePath(SignalMessageDatabase.DATABASE_NAME).exists())
        assertEquals(emptyList(), db.getConversations())
        db.upsertConversation(jid = "new", name = "New chat", isGroup = false)
        assertEquals(listOf("new"), db.getConversations().map { it.jid })
        db.close()
    }

    @Test
    fun protocolDatabaseIsEmptyAndUsableAfterCloseAndDelete() {
        val db = SignalProtocolDatabase(context)
        db.putState("aci", "old-account".toByteArray())

        db.closeAndDelete(context)

        assertNull(db.getState("aci"))
        db.putState("aci", "new-account".toByteArray())
        assertEquals("new-account", db.getState("aci")?.let { String(it) })
        db.close()
    }
}
