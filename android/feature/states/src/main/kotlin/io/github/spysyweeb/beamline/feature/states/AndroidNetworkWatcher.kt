package io.github.spysyweeb.beamline.feature.states

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.github.spysyweeb.beamline.download.NetworkState
import io.github.spysyweeb.beamline.download.net.CatalogAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's connection as the downloader needs it, by the kind of catalog host
 * (docs/architecture-downloads.md 5.2). Needs ACCESS_NETWORK_STATE; reads network state only and
 * sends nothing.
 * - Public host (every release build): the default network; connected = INTERNET and VALIDATED,
 *   unmetered = NOT_METERED.
 * - Loopback host (debug, USB with adb reverse): always connected and unmetered.
 * - Private IPv4 host (debug: the emulator's 10.0.2.2, the PC on the home Wi-Fi): any Wi-Fi or
 *   Ethernet network, validated or not, counts as connected and unmetered, and [boundNetwork] is
 *   that network, so PackHttp's sockets go over it even when mobile data is the default; with no
 *   such network the default network is used, validated or not, and is unmetered only when it has
 *   NOT_METERED (the traffic no longer stays in the home network).
 * [NetworkState.detail] names the condition that failed, never an address.
 */
class AndroidNetworkWatcher(context: Context, address: CatalogAddress) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val host = hostOf(address.url)
    private val loopback = host == "localhost" || host.startsWith("127.")
    private val private = address.local && !loopback
    private val local = ConcurrentHashMap<Network, NetworkCapabilities>()
    @Volatile private var default: Pair<Network, NetworkCapabilities>? = null
    private val _state = MutableStateFlow(if (loopback) NetworkState(connected = true, unmetered = true) else NetworkState.NONE)

    val state: StateFlow<NetworkState> get() = _state

    /** For a private host: the Wi-Fi or Ethernet network PackHttp binds its sockets to; null otherwise (the system decides). */
    fun boundNetwork(): Network? = if (private) local.keys.firstOrNull() ?: default?.first else null

    init {
        if (!loopback) {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    default = network to caps
                    publish()
                }

                override fun onLost(network: Network) {
                    if (default?.first == network) default = null
                    publish()
                }
            })
        }
        if (private) {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    local[network] = caps
                    publish()
                }

                override fun onLost(network: Network) {
                    local.remove(network)
                    publish()
                }
            })
        }
        publish()
    }

    private fun publish() {
        _state.value = when {
            loopback -> NetworkState(connected = true, unmetered = true)
            private -> {
                val d = default
                when {
                    local.isNotEmpty() -> NetworkState(connected = true, unmetered = true)
                    // No Wi-Fi or Ethernet: the default network carries the traffic, validated or not. The
                    // traffic then does not stay in the home network, so "unmetered" is that network's own
                    // NOT_METERED (an emulator on cellular only shows the mobile-data sheet, 10.3).
                    d != null -> {
                        val unmetered = d.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                        NetworkState(connected = true, unmetered = unmetered, detail = if (unmetered) "no Wi-Fi or Ethernet network" else "no Wi-Fi or Ethernet network; metered")
                    }
                    else -> NetworkState(connected = false, unmetered = false, detail = "no Wi-Fi or Ethernet network")
                }
            }
            else -> {
                val caps = default?.second
                val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
                when {
                    caps == null -> NetworkState(false, false, "no network")
                    !internet || !validated -> NetworkState(false, false, "default network not validated")
                    !unmetered -> NetworkState(true, false, "metered")
                    else -> NetworkState(true, true)
                }
            }
        }
    }

    private companion object {
        fun hostOf(url: String): String = try {
            java.net.URI(url).host?.lowercase() ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}
