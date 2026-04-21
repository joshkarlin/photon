package app.photon.signal.store

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore

class PhotonKyberPreKeyStore(private val db: SignalProtocolDatabase) : KyberPreKeyStore {

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        return db.readableDatabase.rawQuery(
            "SELECT record FROM kyber_prekeys WHERE id = ?",
            arrayOf(kyberPreKeyId.toString()),
        ).use { c ->
            if (c.moveToFirst()) KyberPreKeyRecord(c.getBlob(0))
            else throw InvalidKeyIdException("No Kyber pre-key with ID $kyberPreKeyId")
        }
    }

    override fun loadKyberPreKeys(): MutableList<KyberPreKeyRecord> {
        return db.readableDatabase.rawQuery(
            "SELECT record FROM kyber_prekeys", null,
        ).use { c ->
            val list = mutableListOf<KyberPreKeyRecord>()
            while (c.moveToNext()) list.add(KyberPreKeyRecord(c.getBlob(0)))
            list
        }
    }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        storeKyberPreKey(kyberPreKeyId, record, isLastResort = false)
    }

    fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord, isLastResort: Boolean) {
        db.writableDatabase.insertWithOnConflict(
            "kyber_prekeys",
            null,
            ContentValues().apply {
                put("id", kyberPreKeyId)
                put("record", record.serialize())
                put("is_last_resort", if (isLastResort) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean {
        return db.readableDatabase.rawQuery(
            "SELECT 1 FROM kyber_prekeys WHERE id = ?",
            arrayOf(kyberPreKeyId.toString()),
        ).use { it.moveToFirst() }
    }

    override fun markKyberPreKeyUsed(
        kyberPreKeyId: Int,
        replacementId: Int,
        replacementPublicKey: ECPublicKey,
    ) {
        // For one-time Kyber pre-keys, remove after use.
        // Last-resort keys are not removed.
        db.readableDatabase.rawQuery(
            "SELECT is_last_resort FROM kyber_prekeys WHERE id = ?",
            arrayOf(kyberPreKeyId.toString()),
        ).use { c ->
            if (c.moveToFirst() && c.getInt(0) == 0) {
                db.writableDatabase.delete(
                    "kyber_prekeys", "id = ?", arrayOf(kyberPreKeyId.toString()),
                )
            }
        }
    }
}
