package app.photon.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MuteStateTest {
    private val now = 1_900_000_000L

    @Test
    fun timedMuteLapses() {
        assertTrue(isMutedAt(flag = true, mutedUntil = now + 60, nowSeconds = now))
        assertFalse(isMutedAt(flag = true, mutedUntil = now - 1, nowSeconds = now))
    }

    @Test
    fun foreverAndUnmuted() {
        assertTrue(isMutedAt(flag = true, mutedUntil = -1, nowSeconds = now))
        assertFalse(isMutedAt(flag = false, mutedUntil = 0, nowSeconds = now))
    }

    @Test
    fun tablesWithoutTheColumnUseTheFlag() {
        assertTrue(isMutedAt(flag = true, mutedUntil = null, nowSeconds = now))
        assertFalse(isMutedAt(flag = false, mutedUntil = null, nowSeconds = now))
    }
}

/**
 * Reads a messages.db laid out like the Go bridge's schema, as the app does
 * on device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PhotonDatabaseTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val file = File(context.filesDir, "messages.db")
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                """CREATE TABLE conversations (
                    jid TEXT PRIMARY KEY, name TEXT, is_group INTEGER NOT NULL DEFAULT 0,
                    last_message_id TEXT, last_timestamp INTEGER NOT NULL DEFAULT 0,
                    unread_count INTEGER NOT NULL DEFAULT 0, is_muted INTEGER NOT NULL DEFAULT 0,
                    avatar_url TEXT, updated_at INTEGER NOT NULL DEFAULT 0,
                    muted_until INTEGER NOT NULL DEFAULT 0)""",
            )
            val now = System.currentTimeMillis() / 1000
            for ((jid, isMuted, until) in listOf(
                Triple("lapsed", 1, now - 3600),
                Triple("timed", 1, now + 3600),
                Triple("forever", 1, -1L),
                Triple("open", 0, 0L),
            )) {
                db.execSQL(
                    "INSERT INTO conversations (jid, is_muted, muted_until) VALUES (?, ?, ?)",
                    arrayOf<Any>(jid, isMuted, until),
                )
            }
        }
    }

    @Test
    fun isMuted_followsMuteEndTime() {
        val db = PhotonDatabase(context)
        assertEquals(false, db.getConversation("lapsed")?.isMuted, "an expired 8h mute must not silence the chat")
        assertEquals(true, db.getConversation("timed")?.isMuted)
        assertEquals(true, db.getConversation("forever")?.isMuted)
        assertEquals(false, db.getConversation("open")?.isMuted)
    }
}
