package com.example.tgmusicai.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observes network connectivity status using [ConnectivityManager].
 * Exposes real-time [isOnline] StateFlow to reflect internet availability across the application.
 */
class NetworkObserver(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Backing mutable state; seeded with the real status at construction so early observers don't see a false "offline" flash. */
    private val _isOnline = MutableStateFlow(checkInitialOnlineStatus())
    /** Read-only stream of connectivity state for UI/ViewModels to collect (e.g. to disable streaming actions while offline). */
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    /** Fired by the system on every connectivity change; keeps [_isOnline] in sync in real time. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        /** A network became available. Assume online immediately (capability confirmation follows separately). */
        override fun onAvailable(network: Network) {
            _isOnline.value = true
        }

        /** The active network was lost; re-derive status instead of assuming offline in case another network is still up. */
        override fun onLost(network: Network) {
            _isOnline.value = checkInitialOnlineStatus()
        }

        /** Capabilities changed on an existing network (e.g. VPN attached/detached); recheck actual internet capability. */
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            _isOnline.value = hasInternet
        }
    }

    init {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (_: Exception) {
            // Registration can fail on some OEM ROMs/permission setups; fall back to a one-time
            // synchronous check so isOnline still has a sane initial value instead of throwing.
            _isOnline.value = checkInitialOnlineStatus()
        }
    }

    /** Synchronously checks whether the currently active network reports internet capability. */
    private fun checkInitialOnlineStatus(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Unregisters [networkCallback]. Must be called (e.g. in `onCleared`/`onDestroy`) to avoid leaking the callback. */
    fun unregister() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }
}
