package com.ghoststream.core.model

private val ipv4AddressRegex = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

data class DeviceIdentity(
    val generatedName: String,
    val ipAddress: String,
) {
    val nameWithIp: String
        get() = "$generatedName ($ipAddress)"
}

fun deviceIdentity(ipAddress: String): DeviceIdentity {
    return DeviceIdentity(
        generatedName = DeviceNameGenerator.generateName(ipAddress),
        ipAddress = ipAddress,
    )
}

fun formatGeneratedNameWithIp(ipAddress: String): String = deviceIdentity(ipAddress).nameWithIp

fun formatHistoryPeer(peer: String): String {
    return if (peer.looksLikeIpAddress()) formatGeneratedNameWithIp(peer) else peer
}

private fun String.looksLikeIpAddress(): Boolean {
    if (isBlank()) return false
    if (this == "::1") return true
    if (contains(':')) return true
    return ipv4AddressRegex.matches(this)
}
