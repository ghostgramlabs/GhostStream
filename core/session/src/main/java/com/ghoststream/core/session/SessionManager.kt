package com.ghoststream.core.session

import com.ghoststream.core.model.BlockedClient
import com.ghoststream.core.model.ClientActivity
import com.ghoststream.core.model.ConnectedClient
import com.ghoststream.core.model.NetworkAvailability
import com.ghoststream.core.model.RecentSession
import com.ghoststream.core.model.SessionState
import com.ghoststream.core.model.SharedFolder
import com.ghoststream.core.model.SharedItem
import kotlinx.coroutines.flow.StateFlow

interface SessionManager {
    val sessionState: StateFlow<SessionState>
    val recentSessions: StateFlow<List<RecentSession>>

    fun refreshSelection(
        items: List<SharedItem>,
        folders: List<SharedFolder>,
    )

    fun updateNetworkAvailability(networkAvailability: NetworkAvailability)

    suspend fun startSession(
        port: Int,
        sessionUrl: String,
        hostname: String?,
        items: List<SharedItem>,
        folders: List<SharedFolder>,
        networkAvailability: NetworkAvailability,
        authEnabled: Boolean,
        pin: String?,
    )

    fun updateAdvertisedAccess(
        advertisedName: String?,
        hostname: String?,
    )

    suspend fun stopSession(message: String = "Sharing stopped")

    suspend fun blockClient(ipAddress: String)
    suspend fun unblockClient(ipAddress: String)
    fun isBlocked(ipAddress: String): Boolean
    fun blockedClients(): List<BlockedClient>

    fun observeClient(
        ipAddress: String,
        userAgent: String?,
        activity: ClientActivity,
    )

    fun onTransferStarted(ipAddress: String, activity: ClientActivity, isDownload: Boolean)
    fun onTransferProgress(ipAddress: String, bytes: Long, activity: ClientActivity)
    fun onTransferCompleted(ipAddress: String, activity: ClientActivity, wasDownload: Boolean)

    fun generateToken(ipAddress: String): String
    fun validateToken(token: String?): Boolean
    fun clearBrowserAuth()
    fun isPinValid(pin: String): Boolean
    fun regeneratePin(): String
    fun disconnectAllClients()
}

