package app.photon.signal

import okio.ByteString
import org.signal.core.models.ServiceId

/**
 * A service id carried as both a string and a binary field. Current clients
 * are moving to the binary form, so a missing string must not drop the
 * reference. Binary wins, as in the library's own ServiceId.parseOrNull(String,
 * ByteString). Returns the canonical string form ("uuid" for an ACI); an
 * unparseable string is passed through so the caller's lookup still runs.
 */
internal fun serviceIdString(string: String?, binary: ByteString?): String? {
    val fromBinary = binary?.takeIf { it.size > 0 }?.let { ServiceId.parseOrNull(it.toByteArray()) }
    return fromBinary?.toString()
        ?: string?.takeIf { it.isNotBlank() }?.let { ServiceId.parseOrNull(it)?.toString() ?: it }
}
