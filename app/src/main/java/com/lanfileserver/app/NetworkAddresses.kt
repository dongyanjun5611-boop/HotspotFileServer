package com.lanfileserver.app

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkAddresses {
    fun urls(port: Int): List<String> =
        ipv4Addresses().map { "http://$it:$port" }

    fun ipv4Addresses(): List<String> {
        val addresses = mutableListOf<Pair<Int, String>>()
        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        }.getOrDefault(emptyList())

        interfaces.forEach { networkInterface ->
            if (!runCatching { networkInterface.isUp }.getOrDefault(false) ||
                runCatching { networkInterface.isLoopback }.getOrDefault(true)
            ) {
                return@forEach
            }

            Collections.list(networkInterface.inetAddresses)
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                .forEach { address ->
                    val host = address.hostAddress ?: return@forEach
                    addresses += score(networkInterface.name, address) to host
                }
        }

        return addresses
            .sortedWith(compareByDescending<Pair<Int, String>> { it.first }.thenBy { it.second })
            .map { it.second }
            .distinct()
    }

    private fun score(interfaceName: String, address: Inet4Address): Int {
        val name = interfaceName.lowercase()
        var score = if (address.isSiteLocalAddress) 20 else 0
        if (name.startsWith("ap") || name.contains("wlan") || name.contains("wifi")) score += 50
        if (name.contains("eth")) score += 35
        if (name.contains("p2p")) score -= 30
        if (name.contains("tun") || name.contains("vpn") || name.contains("rmnet")) score -= 60
        return score
    }
}

