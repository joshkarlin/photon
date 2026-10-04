package app.photon.signal.store

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.photon.signal.applySignalVerificationSync
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.IdentityKeyStore.Direction
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.whispersystems.signalservice.internal.push.Verified
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PhotonIdentityKeyStoreTest {
    private lateinit var db: SignalProtocolDatabase
    private lateinit var identities: PhotonIdentityKeyStore
    private val remote = SignalProtocolAddress("00000000-0000-4000-8000-000000000001", 1)
    private val local = SignalProtocolAddress("00000000-0000-4000-8000-000000000002", 2)
    private var clock = 100_000L

    @Before fun setUp() {
        db = SignalProtocolDatabase(ApplicationProvider.getApplicationContext())
        db.putState("aci", local.name.toByteArray())
        db.putState("identity_key_pair", IdentityKeyPair.generate().serialize())
        db.putStateInt("registration_id", 123)
        identities = PhotonIdentityKeyStore(db) { clock }
    }

    @After fun tearDown() { db.close() }

    @Test fun incomingRotationIsAcceptedButOutgoingKeyMismatchIsNot() {
        val old = IdentityKeyPair.generate().publicKey
        val fresh = IdentityKeyPair.generate().publicKey
        identities.saveIdentity(remote, old)
        assertTrue(identities.isTrustedIdentity(remote, fresh, Direction.RECEIVING))
        assertFalse(identities.isTrustedIdentity(remote, fresh, Direction.SENDING))
        identities.saveIdentity(remote, fresh)
        assertFalse(identities.isTrustedIdentity(remote, fresh, Direction.SENDING))
        clock += 5_000
        assertTrue(identities.isTrustedIdentity(remote, fresh, Direction.SENDING))
        assertTrue(identities.getRecord(remote.name)!!.changePending)
    }

    @Test fun verifiedRotationStaysBlockedUntilExactChangeIsAccepted() {
        val old = IdentityKeyPair.generate().publicKey
        val fresh = IdentityKeyPair.generate().publicKey
        identities.applyVerificationSync(remote.name, old, IdentityVerification.VERIFIED)
        identities.saveIdentity(remote, fresh)
        val reviewed = identities.getRecord(remote.name)!!
        clock += 60_000
        identities.saveIdentity(remote, fresh)
        assertEquals(reviewed, identities.getRecord(remote.name))
        assertFalse(identities.isTrustedIdentity(remote, fresh, Direction.SENDING))
        assertTrue(identities.acceptChange(reviewed))
        assertTrue(identities.isTrustedIdentity(remote, fresh, Direction.SENDING))
        assertEquals(IdentityVerification.DEFAULT, identities.getRecord(remote.name)!!.verification)
        assertFalse(identities.getRecord(remote.name)!!.changePending)
    }

    @Test fun staleApprovalCannotAcceptAnotherRotation() {
        identities.saveIdentity(remote, IdentityKeyPair.generate().publicKey)
        identities.saveIdentity(remote, IdentityKeyPair.generate().publicKey)
        val reviewed = identities.getRecord(remote.name)!!
        identities.saveIdentity(remote, IdentityKeyPair.generate().publicKey)
        assertFalse(identities.acceptChange(reviewed))
        assertTrue(identities.getRecord(remote.name)!!.changePending)
    }

    @Test fun ordinarySavesPreserveVerificationAndApproval() {
        val key = IdentityKeyPair.generate().publicKey
        identities.applyVerificationSync(remote.name, key, IdentityVerification.VERIFIED)
        val verified = identities.getRecord(remote.name)
        clock += 50_000
        identities.saveIdentity(remote, key)
        assertEquals(verified, identities.getRecord(remote.name))
    }

    @Test fun ownIdentityCannotBeReplacedOrTrustedOnFirstUse() {
        val impostor = IdentityKeyPair.generate().publicKey
        assertFalse(identities.isTrustedIdentity(local, impostor, Direction.RECEIVING))
        assertFalse(identities.isTrustedIdentity(local, impostor, Direction.SENDING))
        assertFailsWith<IllegalStateException> { identities.saveIdentity(local, impostor) }
        assertTrue(identities.isTrustedIdentity(local, identities.identityKeyPair.publicKey, Direction.RECEIVING))
    }

    @Test fun upgradePreservesExistingIdentityAndSessionRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = IdentityKeyPair.generate().publicKey
        val localKeys = db.getState("identity_key_pair")!!
        db.close()
        context.deleteDatabase("signal_protocol.db")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("signal_protocol.db"), null).use { legacy ->
            legacy.execSQL("CREATE TABLE identities (address TEXT PRIMARY KEY, identity_key BLOB NOT NULL, trust_level INTEGER NOT NULL DEFAULT 0, timestamp INTEGER NOT NULL DEFAULT 0)")
            legacy.execSQL("CREATE TABLE sessions (address TEXT NOT NULL, device_id INTEGER NOT NULL, record BLOB NOT NULL, PRIMARY KEY(address, device_id))")
            legacy.execSQL("CREATE TABLE local_state (key TEXT PRIMARY KEY, value BLOB NOT NULL)")
            legacy.execSQL("INSERT INTO identities VALUES (?, ?, 0, 1000)", arrayOf(remote.name, key.serialize()))
            legacy.execSQL("INSERT INTO sessions VALUES (?, 1, ?)", arrayOf(remote.name, org.signal.libsignal.protocol.state.SessionRecord().serialize()))
            legacy.execSQL("INSERT INTO local_state VALUES ('identity_key_pair', ?)", arrayOf(localKeys))
            legacy.version = 1
        }
        db = SignalProtocolDatabase(context)
        identities = PhotonIdentityKeyStore(db) { clock }
        assertEquals(key, identities.getIdentity(remote))
        assertFalse(identities.getRecord(remote.name)!!.changePending)
        assertTrue(identities.isTrustedIdentity(remote, key, Direction.SENDING))
        assertEquals(1, PhotonSessionStore(db).loadExistingSessions(mutableListOf(remote)).size)
    }

    @Test fun verificationSyncRequiresOurOwnSenderAndDoesNotApplyStaleDefaultKey() {
        val key = IdentityKeyPair.generate().publicKey
        val verified = Verified(
            destinationAci = remote.name, identityKey = key.serialize().toByteString(),
            state = Verified.State.VERIFIED,
        )
        assertFalse(applySignalVerificationSync(identities, verified, remote.name, local.name))
        assertEquals(null, identities.getRecord(remote.name))
        assertTrue(applySignalVerificationSync(identities, verified, local.name, local.name))
        assertEquals(IdentityVerification.VERIFIED, identities.getRecord(remote.name)!!.verification)
        val fresh = IdentityKeyPair.generate().publicKey
        identities.saveIdentity(remote, fresh)
        val changed = identities.getRecord(remote.name)
        val staleDefault = verified.copy(state = Verified.State.DEFAULT)
        applySignalVerificationSync(identities, staleDefault, local.name, local.name)
        assertEquals(changed, identities.getRecord(remote.name))
        applySignalVerificationSync(identities, verified.copy(identityKey = fresh.serialize().toByteString()), local.name, local.name)
        assertTrue(identities.isTrustedIdentity(remote, fresh, Direction.SENDING))
        assertFalse(identities.getRecord(remote.name)!!.changePending)
    }

    @Test fun binaryVerificationDestinationAndMatchingDefaultSyncAreHandled() {
        val uuid = java.util.UUID.fromString(remote.name)
        val binary = java.nio.ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
        val key = IdentityKeyPair.generate().publicKey
        val verified = Verified(
            destinationAciBinary = binary.toByteString(),
            identityKey = key.serialize().toByteString(), state = Verified.State.VERIFIED,
        )
        assertTrue(applySignalVerificationSync(identities, verified, local.name, local.name))
        assertEquals(IdentityVerification.VERIFIED, identities.getRecord(remote.name)!!.verification)
        assertTrue(applySignalVerificationSync(identities, verified.copy(state = Verified.State.DEFAULT), local.name, local.name))
        assertEquals(IdentityVerification.DEFAULT, identities.getRecord(remote.name)!!.verification)
        assertFalse(applySignalVerificationSync(identities, verified.copy(destinationAciBinary = null, destinationAci = local.name), local.name, local.name))
        assertFalse(applySignalVerificationSync(identities, verified.copy(identityKey = byteArrayOf(0).toByteString()), local.name, local.name))
    }

    @Test fun newIdentityDecryptsRealPreKeyMessageAndPreservesArchivedSiblingSession() {
        val photon = PhotonProtocolStore(db, identityStore = identities)
        val localKeys = photon.identityKeyPair
        val signed = ECKeyPair.generate()
        val kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signedSignature = localKeys.privateKey.calculateSignature(signed.publicKey.serialize())
        val kyberSignature = localKeys.privateKey.calculateSignature(kyber.publicKey.serialize())
        photon.storeSignedPreKey(1, SignedPreKeyRecord(1, clock, signed, signedSignature))
        photon.storeLastResortKyberPreKey(1, KyberPreKeyRecord(1, clock, kyber, kyberSignature))
        val bundle = PreKeyBundle(123, local.deviceId, PreKeyBundle.NULL_PRE_KEY_ID, null, 1, signed.publicKey,
            signedSignature, localKeys.publicKey, 1, kyber.publicKey, kyberSignature)

        fun sender(): Pair<InMemorySignalProtocolStore, SessionCipher> {
            val store = InMemorySignalProtocolStore(IdentityKeyPair.generate(), 456)
            SessionBuilder(store, local).process(bundle)
            return store to SessionCipher(store, remote, local)
        }
        val (oldStore, oldCipher) = sender()
        val receiver = SessionCipher(photon, local, remote)
        val first = PreKeySignalMessage(oldCipher.encrypt("before".toByteArray()).serialize())
        assertEquals("before", String(receiver.decrypt(first)))
        // Complete the ratchet so old messages use SignalMessage, not pre-keys.
        val reply = SignalMessage(receiver.encrypt("reply".toByteArray()).serialize())
        oldCipher.decrypt(reply)
        val delayed = SignalMessage(oldCipher.encrypt("delayed".toByteArray()).serialize())
        val sibling = SignalProtocolAddress(remote.name, 3)
        photon.storeSession(sibling, photon.loadSession(remote))
        val originalSibling = photon.loadSession(sibling).serialize()
        identities.applyVerificationSync(remote.name, oldStore.identityKeyPair.publicKey, IdentityVerification.VERIFIED)

        val (newStore, newCipher) = sender()
        val rotated = PreKeySignalMessage(newCipher.encrypt("after".toByteArray()).serialize())
        assertEquals("after", String(receiver.decrypt(rotated)))
        assertEquals(newStore.identityKeyPair.publicKey, identities.getIdentity(remote))
        assertTrue(photon.containsSession(remote))
        assertFalse(photon.containsSession(sibling))
        assertNotNull(photon.loadSession(sibling))
        assertFalse(originalSibling.contentEquals(photon.loadSession(sibling).serialize()))
        assertFailsWith<UntrustedIdentityException> { receiver.encrypt("blocked".toByteArray()) }
        assertTrue(identities.acceptChange(identities.getRecord(remote.name)!!))
        val newReply = SignalMessage(receiver.encrypt("accepted".toByteArray()).serialize())
        assertEquals("accepted", String(newCipher.decrypt(newReply)))
        // The archived session can still decrypt a delayed message; no deletion.
        assertEquals("delayed", String(receiver.decrypt(delayed)))
    }
}
