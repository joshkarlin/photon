package app.photon.signal.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * v3 → v4 upgrade on a database with real data: rows the user already saw in
 * Photon (beyond the conversation's unread count) become 'seen', so a later
 * primary read sync can't subtract them from newer messages' count.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SignalMessageDatabaseUpgradeTest {

    @Test
    fun upgradeMarksRowsBeyondUnreadCountSeen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.openOrCreateDatabase(SignalMessageDatabase.DATABASE_NAME, Context.MODE_PRIVATE, null).use { v3 ->
            v3.execSQL(
                "CREATE TABLE conversations (jid TEXT PRIMARY KEY, name TEXT, is_group INTEGER NOT NULL DEFAULT 0, " +
                    "last_message_id TEXT, last_timestamp INTEGER NOT NULL DEFAULT 0, " +
                    "unread_count INTEGER NOT NULL DEFAULT 0, is_muted INTEGER NOT NULL DEFAULT 0, avatar_url TEXT, " +
                    "updated_at INTEGER NOT NULL DEFAULT 0, master_key BLOB, group_revision INTEGER NOT NULL DEFAULT 0)",
            )
            v3.execSQL(
                "CREATE TABLE messages (id TEXT PRIMARY KEY, conversation_jid TEXT NOT NULL, sender_jid TEXT NOT NULL, " +
                    "timestamp INTEGER NOT NULL, content_type TEXT NOT NULL, text_body TEXT, media_url TEXT, " +
                    "media_mime TEXT, media_size INTEGER, thumbnail_path TEXT, sticker_pack_id TEXT, reply_to_id TEXT, " +
                    "edit_version INTEGER NOT NULL DEFAULT 0, is_from_me INTEGER NOT NULL DEFAULT 0, " +
                    "status TEXT NOT NULL DEFAULT 'sent', raw_proto BLOB)",
            )
            v3.execSQL("CREATE TABLE reactions (message_id TEXT NOT NULL, sender_jid TEXT NOT NULL, emoji TEXT NOT NULL, timestamp INTEGER NOT NULL, PRIMARY KEY (message_id, sender_jid))")
            v3.execSQL("CREATE TABLE participants (conversation_jid TEXT NOT NULL, jid TEXT NOT NULL, display_name TEXT, role TEXT DEFAULT 'member', PRIMARY KEY (conversation_jid, jid))")
            v3.execSQL("CREATE TABLE contacts (jid TEXT PRIMARY KEY, name TEXT, phone_number TEXT, profile_key BLOB, profile_name TEXT, profile_fetched_at INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0)")
            v3.execSQL("INSERT INTO conversations (jid, name, unread_count) VALUES ('a', 'A', 1), ('b', 'B', 0)")
            for ((id, jid, ts) in listOf(Triple("a_1_x", "a", 1), Triple("a_2_x", "a", 2), Triple("a_3_x", "a", 3), Triple("b_1_x", "b", 1))) {
                v3.execSQL(
                    "INSERT INTO messages (id, conversation_jid, sender_jid, timestamp, content_type, text_body, is_from_me, status) " +
                        "VALUES (?, ?, ?, ?, 'text', 'm', 0, 'received')",
                    arrayOf<Any>(id, jid, jid, ts),
                )
            }
            v3.version = 3
        }

        val db = SignalMessageDatabase(context)
        try {
            // Conversation a had 1 unread: only its newest row is still unread.
            assertEquals("seen", db.getMessage("a_1_x")?.status)
            assertEquals("seen", db.getMessage("a_2_x")?.status)
            assertEquals("received", db.getMessage("a_3_x")?.status)
            assertEquals("seen", db.getMessage("b_1_x")?.status)
            assertEquals(listOf("a_1_x", "a_2_x"), db.getUnsyncedIncomingReads("a").sorted())
        } finally {
            db.close()
        }
    }
}
