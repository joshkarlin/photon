package app.photon.signal.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.photon.data.model.MessageStatus
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Upgrades in place, on databases holding real data. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SignalMessageDatabaseUpgradeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The tables as v3, v4 and v5 created them. */
    private fun createOldSchema(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE conversations (jid TEXT PRIMARY KEY, name TEXT, is_group INTEGER NOT NULL DEFAULT 0, " +
                "last_message_id TEXT, last_timestamp INTEGER NOT NULL DEFAULT 0, " +
                "unread_count INTEGER NOT NULL DEFAULT 0, is_muted INTEGER NOT NULL DEFAULT 0, avatar_url TEXT, " +
                "updated_at INTEGER NOT NULL DEFAULT 0, master_key BLOB, group_revision INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL(
            "CREATE TABLE messages (id TEXT PRIMARY KEY, conversation_jid TEXT NOT NULL, sender_jid TEXT NOT NULL, " +
                "timestamp INTEGER NOT NULL, content_type TEXT NOT NULL, text_body TEXT, media_url TEXT, " +
                "media_mime TEXT, media_size INTEGER, thumbnail_path TEXT, sticker_pack_id TEXT, reply_to_id TEXT, " +
                "edit_version INTEGER NOT NULL DEFAULT 0, is_from_me INTEGER NOT NULL DEFAULT 0, " +
                "status TEXT NOT NULL DEFAULT 'sent', raw_proto BLOB)",
        )
        db.execSQL("CREATE TABLE reactions (message_id TEXT NOT NULL, sender_jid TEXT NOT NULL, emoji TEXT NOT NULL, timestamp INTEGER NOT NULL, PRIMARY KEY (message_id, sender_jid))")
        db.execSQL("CREATE TABLE participants (conversation_jid TEXT NOT NULL, jid TEXT NOT NULL, display_name TEXT, role TEXT DEFAULT 'member', PRIMARY KEY (conversation_jid, jid))")
        db.execSQL("CREATE TABLE contacts (jid TEXT PRIMARY KEY, name TEXT, phone_number TEXT, profile_key BLOB, profile_name TEXT, profile_fetched_at INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0)")
    }

    /**
     * v3 → v4: rows the user already saw in Photon (beyond the conversation's
     * unread count) become 'seen', so a later primary read sync can't
     * subtract them from newer messages' count.
     */
    @Test
    fun upgradeMarksRowsBeyondUnreadCountSeen() {
        context.openOrCreateDatabase(SignalMessageDatabase.DATABASE_NAME, Context.MODE_PRIVATE, null).use { v3 ->
            createOldSchema(v3)
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
            assertEquals(MessageStatus.SEEN, db.getMessage("a_1_x")?.status)
            assertEquals(MessageStatus.SEEN, db.getMessage("a_2_x")?.status)
            assertEquals(MessageStatus.UNREAD, db.getMessage("a_3_x")?.status)
            assertEquals(MessageStatus.SEEN, db.getMessage("b_1_x")?.status)
            assertEquals(listOf("a_1_x", "a_2_x"), db.getUnsyncedIncomingReads("a").sorted())
        } finally {
            db.close()
        }
    }

    /**
     * v4 → v5: names are resolved when read, so the names older builds
     * stored (DM titles, member labels, the group placeholder) are cleared.
     * Group subjects and every name source in contacts stay.
     */
    @Test
    fun upgradeClearsStoredNamesButKeepsGroupSubjectsAndSources() {
        context.openOrCreateDatabase(SignalMessageDatabase.DATABASE_NAME, Context.MODE_PRIVATE, null).use { v4 ->
            createOldSchema(v4)
            v4.execSQL(
                "INSERT INTO conversations (jid, name, is_group) VALUES " +
                    "('aci-dm', 'Jane (profile)', 0), ('aci-number', '+447700900123', 0), " +
                    "('grp-named', 'Climbing', 1), ('grp-placeholder', 'Signal group', 1), ('grp-raw', 'grp-raw', 1)",
            )
            v4.execSQL("INSERT INTO participants (conversation_jid, jid, display_name) VALUES ('grp-named', 'aci-dm', 'aci-dm'), ('grp-named', 'aci-x', 'Jane')")
            v4.execSQL("INSERT INTO contacts (jid, phone_number, profile_name) VALUES ('aci-dm', '+447700900123', 'Jane')")
            v4.version = 4
        }

        val db = SignalMessageDatabase(context)
        try {
            assertNull(db.getConversation("aci-dm")?.name)
            assertNull(db.getConversation("aci-number")?.name)
            assertEquals("Climbing", db.getConversation("grp-named")?.name)
            assertNull(db.getConversation("grp-placeholder")?.name)
            assertNull(db.getConversation("grp-raw")?.name)
            assertEquals(listOf(null, null), db.getParticipants("grp-named").map { it.displayName })
            assertEquals("+447700900123", db.getContact("aci-dm")?.phone)
            assertEquals("Jane", db.getContact("aci-dm")?.profileName)
        } finally {
            db.close()
        }
    }

    /**
     * v5 → v6: conversations gain muted_until (every chat unmuted until the
     * storage service is read) and the storage tables appear. Nothing else
     * changes.
     */
    @Test
    fun upgradeAddsUnmutedMuteEndsAndStorageTables() {
        context.openOrCreateDatabase(SignalMessageDatabase.DATABASE_NAME, Context.MODE_PRIVATE, null).use { v5 ->
            createOldSchema(v5)
            v5.execSQL("INSERT INTO conversations (jid, name, is_group, unread_count) VALUES ('aci-dm', NULL, 0, 2), ('grp', 'Climbing', 1, 0)")
            v5.execSQL(
                "INSERT INTO messages (id, conversation_jid, sender_jid, timestamp, content_type, text_body, is_from_me, status) " +
                    "VALUES ('aci-dm_1_x', 'aci-dm', 'aci-dm', 1, 'text', 'm', 0, 'received')",
            )
            v5.version = 5
        }

        val db = SignalMessageDatabase(context)
        try {
            assertEquals(6, db.readableDatabase.version)
            val dm = db.getConversation("aci-dm")
            assertEquals(false, dm?.isMuted)
            assertEquals(2, dm?.unreadCount)
            assertEquals("Climbing", db.getConversation("grp")?.name)
            assertEquals(MessageStatus.UNREAD, db.getMessage("aci-dm_1_x")?.status)
            assertNull(db.storageManifestVersion())

            db.applyStorageManifest(4, emptyList(), listOf(StorageMute("s1", "grp", -1)))
            assertEquals(true, db.getConversation("grp")?.isMuted)
            assertEquals(false, db.getConversation("aci-dm")?.isMuted)
        } finally {
            db.close()
        }
    }
}
