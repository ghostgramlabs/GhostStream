package com.ghoststream.core.model

fun SessionState.resolvedAccessUrl(): String? {
    return buildSessionAccessUrl(
        sessionUrl = sessionUrl,
        localAddress = networkAvailability.localAddress,
        port = serverPort,
    )
}

fun SessionState.displayAccessUrl(): String? {
    return buildFriendlyDisplayUrl(
        hostname = hostname,
        port = serverPort,
    ) ?: resolvedAccessUrl()
}

fun buildSessionAccessUrl(
    sessionUrl: String?,
    localAddress: String?,
    port: Int?,
): String? {
    val localUrl = localAddress
        ?.takeIf(::isUsableLocalAddress)
        ?.let { address -> port?.let { resolvedPort -> "http://$address:$resolvedPort" } }

    return sessionUrl?.takeIf(::isUsableSessionUrl) ?: localUrl
}

fun buildFriendlyDisplayUrl(
    hostname: String?,
    port: Int?,
): String? {
    val normalizedHost = hostname
        ?.trim()
        ?.removeSuffix(".")
        ?.takeIf(::isUsableFriendlyHostname)
        ?.let { host ->
            if (host.contains('.')) host else "$host.local"
        }
    return normalizedHost?.let { host -> port?.let { resolvedPort -> "http://$host:$resolvedPort" } }
}

private fun isUsableSessionUrl(url: String): Boolean {
    return url.startsWith("http://") &&
        !url.startsWith("http://:") &&
        !url.startsWith("http://127.0.0.1:") &&
        !url.startsWith("http://0.0.0.0:") &&
        !url.startsWith("http://localhost:")
}

private fun isUsableLocalAddress(address: String): Boolean {
    return address.isNotBlank() &&
        address != "0.0.0.0" &&
        !address.startsWith("127.") &&
        !address.equals("localhost", ignoreCase = true)
}

private fun isUsableFriendlyHostname(hostname: String): Boolean {
    return hostname.isNotBlank() &&
        hostname != "0.0.0.0" &&
        !hostname.startsWith("127.") &&
        !hostname.equals("localhost", ignoreCase = true)
}
