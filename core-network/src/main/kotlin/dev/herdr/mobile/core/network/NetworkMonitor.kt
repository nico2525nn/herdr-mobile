package dev.herdr.mobile.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Emits the current usable-network state. The client calls [HerdrClient.kick] on a
 * `false → true` edge so a regained network retries immediately instead of sleeping out the
 * backoff.
 */
fun networkAvailable(context: Context): Flow<Boolean> = callbackFlow {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun usable(): Boolean {
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            trySend(usable())
        }

        override fun onLost(network: Network) {
            trySend(usable())
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            trySend(usable())
        }
    }

    trySend(usable())
    manager.registerNetworkCallback(
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build(),
        callback,
    )
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()