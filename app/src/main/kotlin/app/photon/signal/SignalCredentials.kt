package app.photon.signal

import app.photon.signal.store.SignalProtocolDatabase
import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.api.util.CredentialsProvider

class SignalCredentials(private val db: SignalProtocolDatabase) : CredentialsProvider {

    var phoneNumber: String?
        get() = db.getState("phone_number")?.let { String(it) }
        set(v) { if (v != null) db.putState("phone_number", v.toByteArray()) }

    var storedDeviceId: Int
        get() = db.getStateInt("device_id") ?: 1
        set(v) { db.putStateInt("device_id", v) }

    var storedPassword: String?
        get() = db.getState("password")?.let { String(it) }
        set(v) { if (v != null) db.putState("password", v.toByteArray()) }

    var aciString: String?
        get() = db.getState("aci")?.let { String(it) }
        set(v) { if (v != null) db.putState("aci", v.toByteArray()) }

    var pniString: String?
        get() = db.getState("pni")?.let { String(it) }
        set(v) { if (v != null) db.putState("pni", v.toByteArray()) }

    fun isRegistered(): Boolean {
        return phoneNumber != null && storedPassword != null && aciString != null
    }

    fun clear() {
        db.deleteAll()
    }

    // CredentialsProvider implementation

    override fun getAci(): ServiceId.ACI? {
        return aciString?.let { ServiceId.ACI.parseOrNull(it) }
    }

    override fun getPni(): ServiceId.PNI? {
        return pniString?.let { ServiceId.PNI.parseOrNull(it) }
    }

    override fun getE164(): String? = phoneNumber

    override fun getDeviceId(): Int = storedDeviceId

    override fun getPassword(): String? = storedPassword
}
