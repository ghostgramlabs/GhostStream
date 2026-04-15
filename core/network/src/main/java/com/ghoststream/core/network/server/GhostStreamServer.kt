package com.ghoststream.core.network.server

data class ServerBinding(
    val port: Int,
    val url: String,
    val hostname: String? = null,
)

interface GhostStreamServer {
    suspend fun start(port: Int): ServerBinding
    suspend fun stop()
    fun isRunning(): Boolean
}

