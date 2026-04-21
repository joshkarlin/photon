package app.photon.signal.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SignalProtocolDatabase(context: Context) : SQLiteOpenHelper(
    context, "signal_protocol.db", null, 1,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS identities (
                address TEXT NOT NULL,
                identity_key BLOB NOT NULL,
                trust_level INTEGER NOT NULL DEFAULT 0,
                timestamp INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (address)
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sessions (
                address TEXT NOT NULL,
                device_id INTEGER NOT NULL,
                record BLOB NOT NULL,
                PRIMARY KEY (address, device_id)
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS prekeys (
                id INTEGER PRIMARY KEY,
                record BLOB NOT NULL
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS signed_prekeys (
                id INTEGER PRIMARY KEY,
                record BLOB NOT NULL,
                timestamp INTEGER NOT NULL DEFAULT 0
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS kyber_prekeys (
                id INTEGER PRIMARY KEY,
                record BLOB NOT NULL,
                is_last_resort INTEGER NOT NULL DEFAULT 0
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sender_keys (
                address TEXT NOT NULL,
                device_id INTEGER NOT NULL,
                distribution_id TEXT NOT NULL,
                record BLOB NOT NULL,
                PRIMARY KEY (address, device_id, distribution_id)
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS local_state (
                key TEXT PRIMARY KEY,
                value BLOB NOT NULL
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // Local state helpers

    fun putState(key: String, value: ByteArray) {
        writableDatabase.insertWithOnConflict(
            "local_state",
            null,
            ContentValues().apply {
                put("key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun getState(key: String): ByteArray? {
        return readableDatabase.rawQuery(
            "SELECT value FROM local_state WHERE key = ?", arrayOf(key),
        ).use { c ->
            if (c.moveToFirst()) c.getBlob(0) else null
        }
    }

    fun putStateInt(key: String, value: Int) {
        putState(key, value.toString().toByteArray())
    }

    fun getStateInt(key: String): Int? {
        return getState(key)?.let { String(it).toIntOrNull() }
    }

    fun deleteAll() {
        writableDatabase.apply {
            delete("identities", null, null)
            delete("sessions", null, null)
            delete("prekeys", null, null)
            delete("signed_prekeys", null, null)
            delete("kyber_prekeys", null, null)
            delete("sender_keys", null, null)
            delete("local_state", null, null)
        }
    }
}
