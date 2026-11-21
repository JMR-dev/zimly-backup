package app.zimly.backup.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the phone has a network that reaches the internet now: the sync work's CONNECTED
 * constraint. A snapshot, for saying what a waiting sync is waiting for.
 */
internal fun hasNetwork(context: Context): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
