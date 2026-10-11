package app.photon.signal.receiver

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class NetworkMonitorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val connectivity = shadowOf(context.getSystemService(ConnectivityManager::class.java))

    private fun capabilities(validated: Boolean): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { caps ->
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (validated) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

    /** The callback lives exactly as long as the receiver runs. */
    @Test
    fun registersOnceOnStartAndUnregistersOnStop() {
        val monitor = NetworkMonitor(context) {}

        monitor.start()
        monitor.start()
        assertEquals(1, connectivity.networkCallbacks.size)

        monitor.stop()
        monitor.stop()
        assertEquals(0, connectivity.networkCallbacks.size)

        monitor.start()
        assertEquals(1, connectivity.networkCallbacks.size)
        monitor.stop()
    }

    @Test
    fun reportsEachTimeTheDefaultNetworkBecomesValidated() {
        var validations = 0
        val monitor = NetworkMonitor(context) { validations++ }
        monitor.start()
        val callback = connectivity.networkCallbacks.single()
        val wifi = ShadowNetwork.newInstance(1)
        val cell = ShadowNetwork.newInstance(2)

        callback.onCapabilitiesChanged(wifi, capabilities(validated = false))
        callback.onCapabilitiesChanged(wifi, capabilities(validated = true))
        callback.onCapabilitiesChanged(wifi, capabilities(validated = true))
        callback.onLost(wifi)
        callback.onCapabilitiesChanged(cell, capabilities(validated = true))

        assertEquals(2, validations)
        monitor.stop()
    }
}
