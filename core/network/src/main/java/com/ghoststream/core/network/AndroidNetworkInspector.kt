package com.ghoststream.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import com.ghoststream.core.model.DebugLogSink
import com.ghoststream.core.model.NetworkAvailability
import com.ghoststream.core.model.NetworkType
import com.ghoststream.core.model.NoOpDebugLogSink
import java.net.Inet4Address
import java.net.NetworkInterface

class AndroidNetworkInspector(
    private val context: Context,
    private val debugLogSink: DebugLogSink = NoOpDebugLogSink,
) {

    fun inspect(): NetworkAvailability {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let(connectivityManager::getNetworkCapabilities)
        val linkProperties = activeNetwork?.let(connectivityManager::getLinkProperties)
        val activeAddress = resolveActiveNetworkIpv4Address(linkProperties)
        val interfaces = resolveLocalIpv4Interfaces()
        val hotspotInterface = interfaces.firstOrNull { isHotspotInterface(it.name) }
        val wifiInterface = interfaces.firstOrNull { isWifiInterface(it.name) }
        val preferredNonCellularInterface = interfaces.firstOrNull { !isCellularInterface(it.name) && isPreferredLocalIpv4Address(it.address) }
            ?: interfaces.firstOrNull { !isCellularInterface(it.name) }
        val fallbackInterface = hotspotInterface ?: wifiInterface ?: preferredNonCellularInterface ?: interfaces.firstOrNull()
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isEthernet = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val isCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val inferredHotspot = hotspotInterface != null
        val inferredWifi = wifiInterface != null
        val interfacesSummary = interfaces.joinToString { candidate -> "${candidate.name}:${candidate.address}" }
        val localAddress = when {
            isWifi || isEthernet -> activeAddress ?: wifiInterface?.address ?: hotspotInterface?.address ?: fallbackInterface?.address
            inferredHotspot -> hotspotInterface?.address ?: wifiInterface?.address ?: fallbackInterface?.address ?: activeAddress
            inferredWifi -> wifiInterface?.address ?: fallbackInterface?.address ?: activeAddress
            isCellular -> hotspotInterface?.address ?: wifiInterface?.address ?: preferredNonCellularInterface?.address
            activeAddress != null && isPreferredLocalIpv4Address(activeAddress) -> activeAddress
            else -> hotspotInterface?.address ?: wifiInterface?.address ?: preferredNonCellularInterface?.address ?: fallbackInterface?.address ?: activeAddress
        }
        debugLogSink.log(
            tag = "NetworkInspector",
            message = "inspect wifi=$isWifi ethernet=$isEthernet cellular=$isCellular activeAddress=$activeAddress hotspot=${hotspotInterface?.name}:${hotspotInterface?.address} wifiInterface=${wifiInterface?.name}:${wifiInterface?.address} chosen=$localAddress interfaces=$interfacesSummary",
        )

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

            inferredWifi && localAddress != null -> {
                NetworkAvailability(
                    type = NetworkType.WIFI,
                    localAddress = localAddress,
                    isReady = true,
                    helperText = "DirectServe found a Wi-Fi address on this device. Nearby devices on the same network should be able to open the link.",
                )
            }

            inferredHotspot && localAddress != null -> {
                NetworkAvailability(
                    type = NetworkType.HOTSPOT,
                    localAddress = localAddress,
                    isReady = true,
                    helperText = "Your hotspot looks ready for nearby access. Connect the other device to this hotspot and open the link.",
                )
            }

            isCellular -> {
                NetworkAvailability(
                    type = NetworkType.NONE,
                    localAddress = null,
                    isReady = false,
                    helperText = "Mobile data alone won't create a local DirectServe session. Use the same Wi-Fi or turn on your hotspot.",
                )
            }

            localAddress != null -> {
                NetworkAvailability(
                    type = if (activeNetwork != null) NetworkType.LOCAL else NetworkType.HOTSPOT,
                    localAddress = localAddress,
                    isReady = true,
                    helperText = "DirectServe found a usable local address on this device. Nearby devices on the same Wi-Fi or hotspot should be able to connect.",
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
        val addresses = linkProperties
            ?.linkAddresses
            ?.asSequence()
            ?.mapNotNull { it.address as? Inet4Address }
            ?.map { it.hostAddress.orEmpty() }
            ?.filter(::isUsableIpv4Address)
            ?.toList()
            .orEmpty()
        return addresses.firstOrNull(::isPreferredLocalIpv4Address)
            ?: addresses.firstOrNull()
    }

    private fun resolveLocalIpv4Interfaces(): List<LocalIpv4Interface> {
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
                        .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                        .map { address ->
                            LocalIpv4Interface(
                                name = networkInterface.name,
                                address = address.hostAddress.orEmpty(),
                            )
                        }
                }
                .filter { candidate -> isUsableIpv4Address(candidate.address) }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun networkInterfacePreference(name: String): Int {
        return when {
            name.startsWith("ap", ignoreCase = true) -> 0
            name.startsWith("softap", ignoreCase = true) -> 1
            name.startsWith("swlan", ignoreCase = true) -> 2
            name.startsWith("wlan", ignoreCase = true) -> 3
            name.startsWith("rndis", ignoreCase = true) -> 4
            name.startsWith("usb", ignoreCase = true) -> 5
            name.startsWith("eth", ignoreCase = true) -> 6
            else -> 10
        }
    }

    private fun isHotspotInterface(name: String): Boolean {
        return name.startsWith("ap", ignoreCase = true) ||
            name.startsWith("softap", ignoreCase = true) ||
            name.startsWith("swlan", ignoreCase = true) ||
            name.startsWith("rndis", ignoreCase = true) ||
            name.startsWith("usb", ignoreCase = true)
    }

    private fun isWifiInterface(name: String): Boolean {
        return name.startsWith("wlan", ignoreCase = true) ||
            name.startsWith("wl", ignoreCase = true) ||
            name.startsWith("p2p", ignoreCase = true) ||
            name.startsWith("wifi", ignoreCase = true)
    }

    private fun isCellularInterface(name: String): Boolean {
        return name.startsWith("rmnet", ignoreCase = true) ||
            name.startsWith("ccmni", ignoreCase = true) ||
            name.startsWith("pdp", ignoreCase = true) ||
            name.startsWith("v4-rmnet", ignoreCase = true) ||
            name.startsWith("wwan", ignoreCase = true)
    }

    private fun isPreferredLocalIpv4Address(address: String): Boolean {
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

    private fun isUsableIpv4Address(address: String): Boolean {
        return address.isNotBlank() &&
            address != "0.0.0.0" &&
            !address.startsWith("127.") &&
            !address.startsWith("169.254.")
    }

    private data class LocalIpv4Interface(
        val name: String,
        val address: String,
    )
}
