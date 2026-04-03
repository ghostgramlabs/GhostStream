package com.ghoststream.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import com.ghoststream.core.model.NetworkAvailability
import com.ghoststream.core.model.NetworkType
import java.net.Inet4Address
import java.net.NetworkInterface

class AndroidNetworkInspector(
    private val context: Context,
) {

    fun inspect(): NetworkAvailability {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let(connectivityManager::getNetworkCapabilities)
        val linkProperties = activeNetwork?.let(connectivityManager::getLinkProperties)
        val localAddress = resolveActiveNetworkIpv4Address(linkProperties) ?: resolveLocalIpv4Address()
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isEthernet = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true

        return when {
            isWifi && localAddress != null -> {
                NetworkAvailability(
                    type = NetworkType.WIFI,
                    localAddress = localAddress,
                    isReady = true,
                    helperText = "Nearby devices can join the same Wi-Fi and open this link. If a public Wi-Fi blocks local access, switch to your hotspot.",
                )
            }

            isEthernet && localAddress != null -> {
                NetworkAvailability(
                    type = NetworkType.LOCAL,
                    localAddress = localAddress,
                    isReady = true,
                    helperText = "Your local network is ready for nearby browser access.",
                )
            }

            localAddress != null -> {
                NetworkAvailability(
                    type = NetworkType.HOTSPOT,
                    localAddress = localAddress,
                    isReady = true,
                    helperText = "Your hotspot or local network looks ready for nearby access.",
                )
            }

            else -> {
                NetworkAvailability(
                    type = NetworkType.NONE,
                    localAddress = null,
                    isReady = false,
                    helperText = "Connect both devices to the same Wi-Fi or hotspot.",
                )
            }
        }
    }

    private fun resolveActiveNetworkIpv4Address(linkProperties: LinkProperties?): String? {
        return linkProperties
            ?.linkAddresses
            ?.asSequence()
            ?.mapNotNull { it.address as? Inet4Address }
            ?.map { it.hostAddress.orEmpty() }
            ?.firstOrNull(::isPrivateIpv4Address)
    }

    private fun resolveLocalIpv4Address(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { networkInterface ->
                    runCatching { networkInterface.isUp && !networkInterface.isLoopback && !networkInterface.isVirtual }
                        .getOrDefault(true)
                }
                .sortedBy { networkInterfacePreference(it.name) }
                .flatMap { networkInterface ->
                    networkInterface.inetAddresses.toList()
                        .asSequence()
                        .filterIsInstance<Inet4Address>()
                        .filterNot { it.isLoopbackAddress }
                        .map { address -> networkInterface.name to address.hostAddress.orEmpty() }
                }
                .firstOrNull { (_, address) -> isPrivateIpv4Address(address) }
                ?.second
        }.getOrNull()
    }

    private fun networkInterfacePreference(name: String): Int {
        return when {
            name.startsWith("ap", ignoreCase = true) -> 0
            name.startsWith("swlan", ignoreCase = true) -> 1
            name.startsWith("wlan", ignoreCase = true) -> 2
            name.startsWith("rndis", ignoreCase = true) -> 3
            name.startsWith("usb", ignoreCase = true) -> 4
            name.startsWith("eth", ignoreCase = true) -> 5
            else -> 10
        }
    }

    private fun isPrivateIpv4Address(address: String): Boolean {
        return address.startsWith("192.168.") ||
            address.startsWith("10.") ||
            address.startsWith("172.16.") ||
            address.startsWith("172.17.") ||
            address.startsWith("172.18.") ||
            address.startsWith("172.19.") ||
            address.startsWith("172.20.") ||
            address.startsWith("172.21.") ||
            address.startsWith("172.22.") ||
            address.startsWith("172.23.") ||
            address.startsWith("172.24.") ||
            address.startsWith("172.25.") ||
            address.startsWith("172.26.") ||
            address.startsWith("172.27.") ||
            address.startsWith("172.28.") ||
            address.startsWith("172.29.") ||
            address.startsWith("172.30.") ||
            address.startsWith("172.31.")
    }
}
