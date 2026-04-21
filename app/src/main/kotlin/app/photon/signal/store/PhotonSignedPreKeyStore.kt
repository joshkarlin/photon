package app.photon.signal.store

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore

class PhotonSignedPreKeyStore(private val db: SignalProtocolDatabase) : SignedPreKeyStore {

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        return db.readableDatabase.rawQuery(
            "SELECT record FROM signed_prekeys WHERE id = ?",
            arrayOf(signedPreKeyId.toString()),
        ).use { c ->
            if (c.moveToFirst()) SignedPreKeyRecord(c.getBlob(0))
            else throw InvalidKeyIdException("No signed pre-key with ID $signedPreKeyId")
        }
    }

    override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> {
        return db.readableDatabase.rawQuery(
            "SELECT record FROM signed_prekeys", null,
        ).use { c ->
            val list = mutableListOf<SignedPreKeyRecord>()
            while (c.moveToNext()) list.add(SignedPreKeyRecord(c.getBlob(0)))
            list
        }
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        db.writableDatabase.insertWithOnConflict(
            "signed_prekeys",
            null,
            ContentValues().apply {
                put("id", signedPreKeyId)
                put("record", record.serialize())
                put("timestamp", record.timestamp)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
        return db.readableDatabase.rawQuery(
            "SELECT 1 FROM signed_prekeys WHERE id = ?",
            arrayOf(signedPreKeyId.toString()),
        ).use { it.moveToFirst() }
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        db.writableDatabase.delete(
            "signed_prekeys", "id = ?", arrayOf(signedPreKeyId.toString()),
        )
    }
}
