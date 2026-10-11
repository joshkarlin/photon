package app.photon.sms

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.Telephony
import org.robolectric.Robolectric

/**
 * The system SMS store on an in-memory SQLite table, so selections filter as
 * the real provider's do. [selections] records what each query asked for.
 */
class FakeSmsProvider : ContentProvider() {
    private lateinit var db: SQLiteDatabase

    val selections = mutableListOf<String?>()

    /** Throw from every query, as when READ_SMS is revoked mid-session. */
    @Volatile var denied = false

    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        db.execSQL(
            "CREATE TABLE sms (_id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id INTEGER, address TEXT, " +
                "body TEXT, date INTEGER, read INTEGER NOT NULL DEFAULT 0, seen INTEGER NOT NULL DEFAULT 0, type INTEGER)",
        )
        return true
    }

    /** Store an SMS as Android would; returns its id. */
    fun add(address: String, body: String, dateMillis: Long, type: Int, thread: Long, read: Boolean = true): String =
        ContentUris.parseId(
            insert(
                Telephony.Sms.CONTENT_URI,
                ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, address)
                    put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, dateMillis)
                    put(Telephony.Sms.TYPE, type)
                    put(Telephony.Sms.THREAD_ID, thread)
                    put(Telephony.Sms.READ, if (read) 1 else 0)
                },
            ),
        ).toString()

    @Synchronized
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        if (denied) throw SecurityException("READ_SMS revoked")
        selections += selection
        return db.query("sms", projection, selection, selectionArgs, null, null, sortOrder)
    }

    @Synchronized
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val id = db.insert("sms", null, values)
        context!!.contentResolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
        return ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)
    }

    override fun getType(uri: Uri): String? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    // Photon isn't the default SMS app: the provider ignores its writes.
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        fun install(): FakeSmsProvider = Robolectric.buildContentProvider(FakeSmsProvider::class.java).create("sms").get()
    }
}
