package com.lanfileserver.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat

data class DeviceLanStatus(
    val networkName: String,
    val lanUrl: String,
    val lanAccessCode: String,
    val lanServerRunning: Boolean,
)

object DeviceLanStatusReader {
    fun read(context: Context): DeviceLanStatus {
        val serverRunning = FileServerService.running
        val lanUrl = if (serverRunning) {
            NetworkAddresses.urls(AppPreferences.port(context)).firstOrNull().orEmpty()
        } else {
            ""
        }
        return DeviceLanStatus(
            networkName = networkName(context, lanUrl),
            lanUrl = lanUrl,
            lanAccessCode = AppPreferences.pin(context),
            lanServerRunning = serverRunning,
        )
    }

    fun hasNetworkNamePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    private fun networkName(context: Context, lanUrl: String): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)

        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            if (!hasNetworkNamePermission(context)) {
                return "Wi-Fi（未授予网络名称权限）"
            }
            val current = readWifiSsid(context, capabilities)
            if (!current.isNullOrBlank()) {
                AppPreferences.saveLastNetworkName(context, current)
                return current
            }
            val cached = AppPreferences.lastNetworkName(context)
            return if (cached.isNullOrBlank()) {
                "Wi-Fi（名称不可用）"
            } else {
                "$cached（最近读取）"
            }
        }

        return when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ->
                "以太网"

            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
                lanUrl.isNotBlank() ->
                "手机热点 / 移动网络（热点名称受系统限制）"

            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ->
                "移动网络"

            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true ->
                "VPN"

            lanUrl.isNotBlank() ->
                "手机热点（名称受系统限制）"

            else ->
                "未连接网络"
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readWifiSsid(
        context: Context,
        capabilities: NetworkCapabilities,
    ): String? {
        val wifiInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                capabilities.transportInfo as? WifiInfo
            } else {
                val wifiManager = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiManager.connectionInfo
            }
        }.getOrNull()
        val raw = wifiInfo?.ssid?.trim().orEmpty()
        return raw
            .removeSurrounding("\"")
            .takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID }
    }
}
