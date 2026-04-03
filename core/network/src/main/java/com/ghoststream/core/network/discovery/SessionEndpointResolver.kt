package com.ghoststream.core.network.discovery

import com.ghoststream.core.model.buildFriendlyDisplayUrl
import com.ghoststream.core.model.buildSessionAccessUrl

data class ResolvedSessionEndpoint(
    val displayUrl: String?,
    val launchUrl: String,
    val hostname: String? = null,
    val address: String,
    val port: Int,
)

class SessionEndpointResolver(
    private val friendlyUrlManager: FriendlyUrlManager = FriendlyUrlManager(),
) {
    fun resolve(
        hostname: String?,
        address: String,
        port: Int,
    ): ResolvedSessionEndpoint {
        val launchUrl = buildSessionAccessUrl(
            sessionUrl = null,
            localAddress = address,
            port = port,
        ) ?: "http://$address:$port"
        val displayUrl = friendlyUrlManager.preferredDisplayUrl(
            hostname = hostname,
            port = port,
            fallbackUrl = buildFriendlyDisplayUrl(hostname = hostname, port = port),
        )
        return ResolvedSessionEndpoint(
            displayUrl = displayUrl,
            launchUrl = launchUrl,
            hostname = hostname,
            address = address,
            port = port,
        )
    }
}
