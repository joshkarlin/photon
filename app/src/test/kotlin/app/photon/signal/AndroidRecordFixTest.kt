package app.photon.signal

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.whispersystems.signalservice.api.account.AccountAttributes
import org.whispersystems.signalservice.api.push.SignedPreKeyEntity
import org.whispersystems.signalservice.internal.push.KyberPreKeyEntity
import org.whispersystems.signalservice.internal.push.LinkDeviceRequest
import org.whispersystems.signalservice.internal.util.JsonUtil
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [AndroidRecordFix] logs and swallows its own failures, so a dependency or
 * toolchain bump that breaks the reflection patch would otherwise only show up
 * as a failed Signal pairing on the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AndroidRecordFixTest {

    @Test
    fun patchedJsonUtilSerializesLinkDeviceRequest() {
        AndroidRecordFix.apply()

        // The JVM's Jackson handles Records natively, so check that the patch
        // itself is what serializes the request.
        val mapper = JsonUtil::class.java.getDeclaredField("objectMapper")
            .apply { isAccessible = true }
            .get(null) as ObjectMapper
        val serializer = mapper.serializerProviderInstance
            .findValueSerializer(LinkDeviceRequest::class.java)
        assertEquals("ReflectiveFieldSerializer", serializer.javaClass.simpleName)

        val signedPreKey = SignedPreKeyEntity(7, ECKeyPair.generate().publicKey, byteArrayOf(1, 2))
        val kyberPreKey = KyberPreKeyEntity(
            9, KEMKeyPair.generate(KEMKeyType.KYBER_1024).publicKey, byteArrayOf(3, 4),
        )
        val request = LinkDeviceRequest(
            "123456",
            AccountAttributes(
                null, 4242, true, null, null, false,
                AccountAttributes.Capabilities(true, true, true, true),
                true, "device-name", 4343, null,
            ),
            signedPreKey,
            signedPreKey,
            kyberPreKey,
            kyberPreKey,
        )

        val json = JsonUtil.toJson(request)
        assertTrue(json.isNotEmpty(), "JsonUtil.toJson swallowed a serialization error")

        val tree = mapper.readTree(json)
        assertEquals(
            setOf(
                "verificationCode", "accountAttributes",
                "aciSignedPreKey", "pniSignedPreKey",
                "aciPqLastResortPreKey", "pniPqLastResortPreKey",
            ),
            tree.fieldNames().asSequence().toSet(),
        )
        assertEquals("123456", tree["verificationCode"].asText())
        assertEquals(4242, tree["accountAttributes"]["registrationId"].asInt())
        assertEquals(7, tree["aciSignedPreKey"]["keyId"].asInt())
        assertEquals(9, tree["pniPqLastResortPreKey"]["keyId"].asInt())
    }
}
