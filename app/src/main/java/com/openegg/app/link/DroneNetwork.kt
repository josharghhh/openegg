package com.openegg.app.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address

/**
 * The PowerEgg base station is a 5 GHz Wi-Fi access point with no internet
 * ("Power_Ground…" / "PowerVision_Ground…" / "PowerEgg_Station_…").
 *
 * Since Android 10 the system routes app traffic to mobile data when the
 * current Wi-Fi has no internet, so sockets to 192.168.1.x silently fail.
 * We request the Wi-Fi network explicitly (without NET_CAPABILITY_INTERNET)
 * and bind the whole process to it while the drone link is active.
 */
class DroneNetwork(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    data class State(
        val network: Network?,
        val localAddress: String?,
        val gateway: String?,
    ) {
        val isUp get() = network != null
    }

    /** Emits link state; binds the process to the Wi-Fi network while collected. */
    fun observe(): Flow<State> = callbackFlow {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                trySend(describe(network, cm.getLinkProperties(network)))
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                trySend(describe(network, lp))
            }

            override fun onLost(network: Network) {
                cm.bindProcessToNetwork(null)
                trySend(State(null, null, null))
            }
        }

        trySend(State(null, null, null))
        cm.requestNetwork(request, callback)
        awaitClose {
            cm.unregisterNetworkCallback(callback)
            cm.bindProcessToNetwork(null)
        }
    }

    private fun describe(network: Network, lp: LinkProperties?): State {
        val local = lp?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address }
            ?.hostAddress
        val gateway = lp?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
        return State(network, local, gateway)
    }

    companion object {
        /** SSID prefixes seen in the manual and in the original app. */
        val BASE_STATION_SSID_PREFIXES = listOf("Power_Ground", "PowerVision_Ground", "PowerEgg_Station_")
    }
}
