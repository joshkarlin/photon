package app.photon.signal.store

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore

class PhotonPreKeyStore(private val db: SignalProtocolDatabase) : PreKeyStore {

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        return db.readableDatabase.rawQuery(
            "SELECT record FROM prekeys WHERE id = ?",
            arrayOf(preKeyId.toString()),
        ).use { c ->
            if (c.moveToFirst()) PreKeyRecord(c.getBlob(0))
            else throw InvalidKeyIdException("No pre-key with ID $preKeyId")
        }
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        db.writableDatabase.insertWithOnConflict(
            "prekeys",
            null,
            ContentValues().apply {
                put("id", preKeyId)
                put("record", record.serialize())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun containsPreKey(preKeyId: Int): Boolean {
        return db.readableDatabase.rawQuery(
            "SELECT 1 FROM prekeys WHERE id = ?",
            arrayOf(preKeyId.toString()),
        ).use { it.moveToFirst() }
    }

    override fun removePreKey(preKeyId: Int) {
        db.writableDatabase.delete("prekeys", "id = ?", arrayOf(preKeyId.toString()))
    }
}
