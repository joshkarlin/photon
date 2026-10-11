package app.photon.data.db

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** The bridge's messages.db tables that Photon reads, as the bridge creates them. */
internal fun createWhatsAppSchema(db: SQLiteDatabase) {
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
}

/** Write to [file] as the bridge does: WAL mode. */
internal fun writeWhatsAppDb(file: File, block: (SQLiteDatabase) -> Unit) =
    SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
        db.enableWriteAheadLogging()
        block(db)
    }
