package app.photon.signal.store

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import java.util.UUID

class PhotonSenderKeyStore(private val db: SignalProtocolDatabase) : SenderKeyStore {

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord,
    ) {
        db.writableDatabase.insertWithOnConflict(
            "sender_keys",
            null,
            ContentValues().apply {
                put("address", sender.name)
                put("device_id", sender.deviceId)
                put("distribution_id", distributionId.toString())
                put("record", record.serialize())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun loadSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
    ): SenderKeyRecord? {
        return db.readableDatabase.rawQuery(
            "SELECT record FROM sender_keys WHERE address = ? AND device_id = ? AND distribution_id = ?",
            arrayOf(sender.name, sender.deviceId.toString(), distributionId.toString()),
        ).use { c ->
            if (c.moveToFirst()) SenderKeyRecord(c.getBlob(0)) else null
        }
    }
}
