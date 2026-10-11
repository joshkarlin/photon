package app.photon.signal.storage

import org.signal.core.models.storageservice.StorageKey
import org.whispersystems.signalservice.api.storage.SignalStorageManifest
import org.whispersystems.signalservice.api.storage.SignalStorageRecord
import org.whispersystems.signalservice.api.storage.StorageId
import org.whispersystems.signalservice.api.storage.StorageServiceApi
import org.whispersystems.signalservice.api.storage.StorageServiceRepository
import org.whispersystems.signalservice.api.storage.StorageServiceRepository.ManifestIfDifferentVersionResult
import org.whispersystems.signalservice.api.storage.StorageServiceRepository.ManifestResult
import org.whispersystems.signalservice.api.storage.StorageServiceRepository.StorageRecordResult
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.internal.push.PushServiceSocket

/** A manifest read: what the storage service holds now, relative to what Photon read last. */
internal sealed interface ManifestFetch {
    data class Changed(val manifest: SignalStorageManifest) : ManifestFetch

    /** Still the version Photon read last. */
    data object Unchanged : ManifestFetch

    /** The account has no manifest yet. */
    data object NotFound : ManifestFetch

    /** The key doesn't decrypt it: the primary has rotated it. */
    data object BadKey : ManifestFetch

    /** Network, auth (401/403) or server failure: try again another time. */
    data class Failed(val reason: String) : ManifestFetch
}

internal sealed interface RecordsFetch {
    data class Read(val records: List<SignalStorageRecord>) : RecordsFetch
    data object BadKey : RecordsFetch
    data class Failed(val reason: String) : RecordsFetch
}

/**
 * The storage service reads Photon makes. Read-only: nothing here writes a
 * manifest or a record. An interface so the sync can be tested without the
 * network.
 */
internal interface StorageReader {
    /** The manifest, or [ManifestFetch.Unchanged] while it's still [knownVersion] (null: read it regardless). */
    fun manifest(key: StorageKey, knownVersion: Long?): ManifestFetch

    /** The records [ids] of [manifest], decrypted. */
    fun records(key: StorageKey, manifest: SignalStorageManifest, ids: List<StorageId>): RecordsFetch
}

/**
 * [StorageReader] through the library's StorageServiceRepository: the
 * storage credential comes over the receiver's authenticated socket
 * (GET /v1/storage/auth) and the reads go to storage.signal.org. The
 * repository derives the manifest key per version and each record's key
 * (from the manifest's recordIkm, or the storage key for older manifests).
 */
internal class LibraryStorageReader(private val repository: StorageServiceRepository) : StorageReader {

    constructor(socket: SignalWebSocket.AuthenticatedWebSocket, pushSocket: PushServiceSocket) :
        this(StorageServiceRepository(StorageServiceApi(socket, pushSocket)))

    override fun manifest(key: StorageKey, knownVersion: Long?): ManifestFetch = try {
        if (knownVersion == null) {
            when (val result = repository.getStorageManifest(key)) {
                is ManifestResult.Success -> ManifestFetch.Changed(result.manifest)
                is ManifestResult.NotFoundError -> ManifestFetch.NotFound
                is ManifestResult.DecryptionError -> ManifestFetch.BadKey
                is ManifestResult.NetworkError -> ManifestFetch.Failed("network: ${result.exception.message}")
                is ManifestResult.StatusCodeError -> ManifestFetch.Failed("HTTP ${result.code}")
            }
        } else {
            when (val result = repository.getStorageManifestIfDifferentVersion(key, knownVersion)) {
                is ManifestIfDifferentVersionResult.DifferentVersion -> ManifestFetch.Changed(result.manifest)
                is ManifestIfDifferentVersionResult.SameVersion -> ManifestFetch.Unchanged
                is ManifestIfDifferentVersionResult.DecryptionError -> ManifestFetch.BadKey
                is ManifestIfDifferentVersionResult.NetworkError -> ManifestFetch.Failed("network: ${result.exception.message}")
                is ManifestIfDifferentVersionResult.StatusCodeError ->
                    if (result.code == 404) ManifestFetch.NotFound else ManifestFetch.Failed("HTTP ${result.code}")
            }
        }
    } catch (e: Exception) {
        // The repository rethrows anything that isn't a network or status error.
        ManifestFetch.Failed("${e.javaClass.simpleName}: ${e.message}")
    }

    override fun records(key: StorageKey, manifest: SignalStorageManifest, ids: List<StorageId>): RecordsFetch = try {
        when (val result = repository.readStorageRecords(key, manifest.recordIkm, ids)) {
            is StorageRecordResult.Success -> RecordsFetch.Read(result.records)
            is StorageRecordResult.DecryptionError -> RecordsFetch.BadKey
            is StorageRecordResult.NetworkError -> RecordsFetch.Failed("network: ${result.exception.message}")
            is StorageRecordResult.StatusCodeError -> RecordsFetch.Failed("HTTP ${result.code}")
        }
    } catch (e: Exception) {
        RecordsFetch.Failed("${e.javaClass.simpleName}: ${e.message}")
    }
}
