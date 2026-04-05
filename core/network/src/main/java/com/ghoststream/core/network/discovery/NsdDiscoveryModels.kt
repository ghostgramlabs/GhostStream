package com.ghoststream.core.network.discovery

import android.net.nsd.NsdServiceInfo

internal const val GHOSTSTREAM_SERVICE_TYPE = "_ghoststream._tcp"

data class AdvertisedSessionInfo(
    val port: Int,
    val sessionId: String,
    val authRequired: Boolean,
    val browserSupported: Boolean = true,
    val streamingSupported: Boolean = true,
    val deviceLabel: String,
)

data class AdvertisedServiceRegistration(
    val serviceName: String,
    val hostname: String? = null,
    val displayUrl: String? = null,
)

internal data class DiscoveryMetadata(
    val version: Int = 1,
    val sessionId: String,
    val authRequired: Boolean,
    val browserSupported: Boolean,
    val streamingSupported: Boolean,
)

internal object DiscoveryMetadataCodec {
    fun applyTo(
        serviceInfo: NsdServiceInfo,
        metadata: DiscoveryMetadata,
    ) {
        serviceInfo.setAttribute("v", metadata.version.toString())
        serviceInfo.setAttribute("sid", metadata.sessionId)
        serviceInfo.setAttribute("pin", if (metadata.authRequired) "1" else "0")
        serviceInfo.setAttribute("br", if (metadata.browserSupported) "1" else "0")
        serviceInfo.setAttribute("str", if (metadata.streamingSupported) "1" else "0")
    }

    fun decode(attributes: Map<String, ByteArray>): DiscoveryMetadata? {
        val sessionId = attributes["sid"]?.decodeToString()?.takeIf { it.isNotBlank() } ?: return null
        val version = attributes["v"]?.decodeToString()?.toIntOrNull() ?: 1
        return DiscoveryMetadata(
            version = version,
            sessionId = sessionId,
            authRequired = attributes["pin"]?.decodeToString() == "1",
            browserSupported = attributes["br"]?.decodeToString() != "0",
            streamingSupported = attributes["str"]?.decodeToString() != "0",
        )
    }
}

internal fun buildAdvertisedServiceName(deviceLabel: String): String {
    val cleaned = deviceLabel
        .replace(Regex("[^A-Za-z0-9 -]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(24)
        .ifBlank { "Android" }
    return "DirectServe $cleaned"
}
