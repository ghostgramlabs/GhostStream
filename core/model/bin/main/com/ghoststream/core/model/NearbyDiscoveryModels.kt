package com.ghoststream.core.model

data class NearbyDevice(
    val id: String,
    val serviceName: String,
    val friendlyUrl: String? = null,
    val launchUrl: String,
    val hostname: String? = null,
    val address: String,
    val port: Int,
    val sessionId: String? = null,
    val authRequired: Boolean = false,
    val browserSupported: Boolean = true,
    val streamingSupported: Boolean = true,
)

data class NearbyDiscoveryState(
    val isDiscovering: Boolean = false,
    val devices: List<NearbyDevice> = emptyList(),
    val helperText: String = "Nearby DirectServe devices on the same Wi-Fi or hotspot will appear here. This is optional.",
    val lastError: String? = null,
)
