package app.photon.signal.store

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore

class PhotonSessionStore(private val db: SignalProtocolDatabase) : SessionStore {

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        return db.readableDatabase.rawQuery(
            "SELECT record FROM sessions WHERE address = ? AND device_id = ?",
            arrayOf(address.name, address.deviceId.toString()),
        ).use { c ->
            if (c.moveToFirst()) SessionRecord(c.getBlob(0)) else SessionRecord()
        }
    }

    override fun loadExistingSessions(
        addresses: MutableList<SignalProtocolAddress>,
    ): MutableList<SessionRecord> {
        return addresses.map { address ->
            db.readableDatabase.rawQuery(
                "SELECT record FROM sessions WHERE address = ? AND device_id = ?",
                arrayOf(address.name, address.deviceId.toString()),
            ).use { c ->
                if (c.moveToFirst()) SessionRecord(c.getBlob(0))
                else throw NoSessionException("No session for $address")
            }
        }.toMutableList()
    }

    override fun getSubDeviceSessions(name: String): MutableList<Int> {
        return db.readableDatabase.rawQuery(
            "SELECT device_id FROM sessions WHERE address = ? AND device_id != 1",
            arrayOf(name),
        ).use { c ->
            val list = mutableListOf<Int>()
            while (c.moveToNext()) list.add(c.getInt(0))
            list
        }
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        db.writableDatabase.insertWithOnConflict(
            "sessions",
            null,
            ContentValues().apply {
                put("address", address.name)
                put("device_id", address.deviceId)
                put("record", record.serialize())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean {
        return db.readableDatabase.rawQuery(
            "SELECT 1 FROM sessions WHERE address = ? AND device_id = ?",
            arrayOf(address.name, address.deviceId.toString()),
        ).use { it.moveToFirst() }
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        db.writableDatabase.delete(
            "sessions",
            "address = ? AND device_id = ?",
            arrayOf(address.name, address.deviceId.toString()),
        )
    }

    override fun deleteAllSessions(name: String) {
        db.writableDatabase.delete("sessions", "address = ?", arrayOf(name))
    }
}
