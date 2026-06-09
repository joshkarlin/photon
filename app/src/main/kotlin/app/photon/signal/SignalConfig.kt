package app.photon.signal

import android.content.Context
import android.util.Base64
import app.photon.R
import org.whispersystems.signalservice.api.SignalSessionLock
import org.whispersystems.signalservice.api.push.TrustStore
import org.whispersystems.signalservice.api.util.CredentialsProvider
import org.whispersystems.signalservice.api.util.SleepTimer
import org.whispersystems.signalservice.api.websocket.HealthMonitor
import org.whispersystems.signalservice.api.websocket.WebSocketFactory
import org.whispersystems.signalservice.internal.configuration.SignalCdnUrl
import org.whispersystems.signalservice.internal.configuration.SignalCdsiUrl
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration
import org.whispersystems.signalservice.internal.configuration.SignalServiceUrl
import org.whispersystems.signalservice.internal.configuration.SignalStorageUrl
import org.whispersystems.signalservice.internal.configuration.SignalSvr2Url
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import org.whispersystems.signalservice.internal.websocket.OkHttpWebSocketConnection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Optional
import java.util.concurrent.locks.ReentrantLock

/**
 * Signal production server configuration.
 * Values from signal-cli's LiveConfig.java.
 */
object SignalConfig {
    const val USER_AGENT = "Signal-Android/7.24.4"

    private const val SERVICE_URL = "https://chat.signal.org"
    private const val CDN_URL = "https://cdn.signal.org"
    private const val CDN2_URL = "https://cdn2.signal.org"
    private const val CDN3_URL = "https://cdn3.signal.org"
    private const val STORAGE_URL = "https://storage.signal.org"
    private const val CDSI_URL = "https://cdsi.signal.org"
    private const val SVR2_URL = "https://svr2.signal.org"

    private const val SVR2_MRENCLAVE = "29cd63c87bea751e3bfd0fbd401279192e2e5c99948b4ee9437eafc4968355fb"

    // Unidentified sender trust root public keys (for sealed sender certificate validation).
    // Signal rotated their server signing infrastructure at some point and added a second
    // trust root; current server certs sign back to either one, so we must include both.
    // Lifted directly from Signal-Android's production buildConfigField.
    val UNIDENTIFIED_SENDER_TRUST_ROOTS = arrayOf(
        "BXu6QIKVz5MA8gstzfOgRQGqyLqOwNKHL6INkv3IHWMF",
        "BUkY0I+9+oPgDCn4+Ac6Iu813yvqkDr/ga8DzLxFxuk6",
    )

    private const val ZK_GROUP_PARAMS = "AMhf5ywVwITZMsff/eCyudZx9JDmkkkbV6PInzG4p8x3VqVJSFiMvnvlEKWuRob/1eaIetR31IYeAbm0NdOuHH8Qi+Rexi1wLlpzIo1gstHWBfZzy1+qHRV5A4TqPp15YzBPm0WSggW6PbSn+F4lf57VCnHF7p8SvzAA2ZZJPYJURt8X7bbg+H3i+PEjH9DXItNEqs2sNcug37xZQDLm7X36nOoGPs54XsEGzPdEV+itQNGUFEjY6X9Uv+Acuks7NpyGvCoKxGwgKgE5XyJ+nNKlyHHOLb6N1NuHyBrZrgtY/JYJHRooo5CEqYKBqdFnmbTVGEkCvJKxLnjwKWf+fEPoWeQFj5ObDjcKMZf2Jm2Ae69x+ikU5gBXsRmoF94GXTLfN0/vLt98KDPnxwAQL9j5V1jGOY8jQl6MLxEs56cwXN0dqCnImzVH3TZT1cJ8SW1BRX6qIVxEzjsSGx3yxF3suAilPMqGRp4ffyopjMD1JXiKR2RwLKzizUe5e8XyGOy9fplzhw3jVzTRyUZTRSZKkMLWcQ/gv0E4aONNqs4P+NameAZYOD12qRkxosQQP5uux6B2nRyZ7sAV54DgFyLiRcq1FvwKw2EPQdk4HDoePrO/RNUbyNddnM/mMgj4FW65xCoT1LmjrIjsv/Ggdlx46ueczhMgtBunx1/w8k8V+l8LVZ8gAT6wkU5J+DPQalQguMg12Jzug3q4TbdHiGCmD9EunCwOmsLuLJkz6EcSYXtrlDEnAM+hicw7iergYLLlMXpfTdGxJCWJmP4zqUFeTTmsmhsjGBt7NiEB/9pFFEB3pSbf4iiUukw63Eo8Aqnf4iwob6X1QviCWuc8t0LUlT9vALgh/f2DPVOOmR0RW6bgRvc7DSF20V/omg+YBw=="

    private const val GENERIC_SERVER_PARAMS = "AByD873dTilmOSG0TjKrvpeaKEsUmIO8Vx9BeMmftwUs9v7ikPwM8P3OHyT0+X3EUMZrSe9VUp26Wai51Q9I8mdk0hX/yo7CeFGJyzoOqn8e/i4Ygbn5HoAyXJx5eXfIbqpc0bIxzju4H/HOQeOpt6h742qii5u/cbwOhFZCsMIbElZTaeU+BWMBQiZHIGHT5IE0qCordQKZ5iPZom0HeFa8Yq0ShuEyAl0WINBiY6xE3H/9WnvzXBbMuuk//eRxXgzO8ieCeK8FwQNxbfXqZm6Ro1cMhCOF3u7xoX83QhpN"

    private const val BACKUP_SERVER_PARAMS = "AJwNSU55fsFCbgaxGRD11wO1juAs8Yr5GF8FPlGzzvdJJIKH5/4CC7ZJSOe3yL2vturVaRU2Cx0n751Vt8wkj1bozK3CBV1UokxV09GWf+hdVImLGjXGYLLhnI1J2TWEe7iWHyb553EEnRb5oxr9n3lUbNAJuRmFM7hrr0Al0F0wrDD4S8lo2mGaXe0MJCOM166F8oYRQqpFeEHfiLnxA1O8ZLh7vMdv4g9jI5phpRBTsJ5IjiJrWeP0zdIGHEssUeprDZ9OUJ14m0v61eYJMKsf59Bn+mAT2a7YfB+Don9O"

    private const val TRUST_STORE_PASSWORD = "whisper"

    private var bksBytes: ByteArray? = null

    /**
     * Must be called once with a Context before using createConfiguration().
     * Builds a BKS keystore from Signal's self-signed root CA.
     */
    fun init(context: Context) {
        if (bksBytes != null) return
        bksBytes = createBksFromSignalCa(context)
    }

    private val trustStore: TrustStore
        get() {
            val bytes = bksBytes ?: throw IllegalStateException("SignalConfig.init() not called")
            return object : TrustStore {
                override fun getKeyStoreInputStream(): InputStream = ByteArrayInputStream(bytes)
                override fun getKeyStorePassword(): String = TRUST_STORE_PASSWORD
            }
        }

    private fun createBksFromSignalCa(context: Context): ByteArray {
        val certFactory = CertificateFactory.getInstance("X.509")
        val signalCa = context.resources.openRawResource(R.raw.signal_ca).use {
            certFactory.generateCertificate(it)
        }

        val bks = KeyStore.getInstance("BKS")
        bks.load(null, TRUST_STORE_PASSWORD.toCharArray())
        bks.setCertificateEntry("signal_ca", signalCa)

        val out = ByteArrayOutputStream()
        bks.store(out, TRUST_STORE_PASSWORD.toCharArray())
        return out.toByteArray()
    }

    /** Thread.sleep-backed timer shared by every Photon websocket. */
    val sleepTimer: SleepTimer = object : SleepTimer {
        override fun sleep(millis: Long) = Thread.sleep(millis)
    }

    /**
     * A fresh ReentrantLock-backed SignalSessionLock. Sender and receiver
     * each keep their own instance (as they always have) — this only
     * removes the duplicated anonymous-object boilerplate.
     */
    fun newSessionLock(): SignalSessionLock = object : SignalSessionLock {
        private val lock = ReentrantLock()
        override fun acquire(): SignalSessionLock.Lock {
            lock.lock()
            return SignalSessionLock.Lock { lock.unlock() }
        }
    }

    /**
     * WebSocketFactory for an OkHttp connection to Signal. Authenticated
     * when [credentials] is non-null, unauthenticated otherwise.
     * [onMessageError] hooks the HealthMonitor callback for callers that
     * want to log message errors; keep-alives are always ignored.
     */
    fun webSocketFactory(
        config: SignalServiceConfiguration,
        name: String,
        credentials: CredentialsProvider?,
        onMessageError: ((Int) -> Unit)? = null,
    ): WebSocketFactory {
        val creds: Optional<CredentialsProvider> =
            if (credentials != null) Optional.of(credentials) else Optional.empty()
        return WebSocketFactory {
            OkHttpWebSocketConnection(
                name,
                config,
                creds,
                USER_AGENT,
                object : HealthMonitor {
                    override fun onKeepAliveResponse(sentTimestamp: Long, isIdentifiedWebSocket: Boolean) {}
                    override fun onMessageError(status: Int, isIdentifiedWebSocket: Boolean) {
                        onMessageError?.invoke(status)
                    }
                },
                credentials != null,
            )
        }
    }

    fun createConfiguration(): SignalServiceConfiguration {
        val serviceUrls = arrayOf(SignalServiceUrl(SERVICE_URL, trustStore))
        val cdnMap = mapOf(
            0 to arrayOf(SignalCdnUrl(CDN_URL, trustStore)),
            2 to arrayOf(SignalCdnUrl(CDN2_URL, trustStore)),
            3 to arrayOf(SignalCdnUrl(CDN3_URL, trustStore)),
        )
        val storageUrls = arrayOf(SignalStorageUrl(STORAGE_URL, trustStore))
        val cdsiUrls = arrayOf(SignalCdsiUrl(CDSI_URL, trustStore))
        val svr2Urls = arrayOf(SignalSvr2Url(SVR2_URL, trustStore, SVR2_MRENCLAVE))

        return SignalServiceConfiguration(
            serviceUrls,
            cdnMap,
            storageUrls,
            cdsiUrls,
            svr2Urls,
            emptyList(),          // network interceptors
            Optional.empty(),     // dns
            Optional.empty(),     // signal proxy
            Optional.empty(),     // http proxy
            Base64.decode(ZK_GROUP_PARAMS, Base64.DEFAULT),
            Base64.decode(GENERIC_SERVER_PARAMS, Base64.DEFAULT),
            Base64.decode(BACKUP_SERVER_PARAMS, Base64.DEFAULT),
            false,                // censored
        )
    }
}

/**
 * Raw authenticated REST call. PushServiceSocket.makeServiceRequest is
 * package-private in the library, so reflection is the only way to hit
 * endpoints it doesn't expose (pre-key upload fallback, linked-device
 * DELETE on logout) without forking it.
 */
fun PushServiceSocket.serviceRequest(path: String, method: String, body: String) {
    val m = javaClass.getDeclaredMethod(
        "makeServiceRequest", String::class.java, String::class.java, String::class.java,
    )
    m.isAccessible = true
    m.invoke(this, path, method, body)
}
