package de.glas.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/** Current connection and Wi-Fi signal for the status bar. */
object Network {
    fun kind(ctx: Context): NetworkIcon.Kind {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return NetworkIcon.Kind.OFFLINE
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkIcon.Kind.OFFLINE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkIcon.Kind.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkIcon.Kind.WIFI
            else -> NetworkIcon.Kind.OFFLINE
        }
    }

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Signal strength from 1 (weak) to 4 (full). */
    @Suppress("DEPRECATION")
    fun wifiLevel(ctx: Context): Int {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return 0
        val rssi = runCatching { wm.connectionInfo.rssi }.getOrNull() ?: return 0
        return WifiManager.calculateSignalLevel(rssi, 4) + 1
    }
}
