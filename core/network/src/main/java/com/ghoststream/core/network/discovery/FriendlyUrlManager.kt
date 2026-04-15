package com.ghoststream.core.network.discovery

import com.ghoststream.core.model.buildFriendlyDisplayUrl

class FriendlyUrlManager {
    fun buildFriendlyUrl(
        hostname: String?,
        port: Int?,
    ): String? = buildFriendlyDisplayUrl(hostname = hostname, port = port)

    fun preferredDisplayUrl(
        hostname: String?,
        port: Int?,
        fallbackUrl: String?,
    ): String? = buildFriendlyUrl(hostname = hostname, port = port) ?: fallbackUrl
}
