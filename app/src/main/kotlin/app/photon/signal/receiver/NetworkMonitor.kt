package app.photon.signal.receiver

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import app.photon.signal.SignalMessageReceiver
import app.photon.signal.DefaultNetworkValidation

private const val TAG = SignalMessageReceiver.TAG

/**
 * Reports when the default network becomes validated (internet reachable),
 * so the receiver can reconnect at once instead of finishing its backoff.
 * Registered while the receiver runs; [stop] unregisters it.
 */
internal class NetworkMonitor(
    context: Context,
    private val onValidated: () -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun start() {
        if (callback != null) return
        val networks = DefaultNetworkValidation()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (networks.onCapabilities(network, validated)) onValidated()
            }

            override fun onLost(network: Network) = networks.onLost(network)
        }
        try {
            connectivity.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (e: RuntimeException) {
            // Without it the receiver still reconnects, on its backoff alone.
            Log.w(TAG, "Couldn't watch network changes: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        val cb = callback ?: return
        callback = null
        try {
            connectivity.unregisterNetworkCallback(cb)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
    }
}
