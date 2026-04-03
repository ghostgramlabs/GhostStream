package com.ghoststream.core.model

fun SessionState.resolvedAccessUrl(): String? {
    return buildSessionAccessUrl(
        sessionUrl = sessionUrl,
        localAddress = networkAvailability.localAddress,
        port = serverPort,
    )
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
