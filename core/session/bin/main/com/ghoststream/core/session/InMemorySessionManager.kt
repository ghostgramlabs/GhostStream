package com.ghoststream.core.session

import com.ghoststream.core.model.BlockedClient
import com.ghoststream.core.model.ClientActivity
import com.ghoststream.core.model.ConnectedClient
import com.ghoststream.core.model.DebugLogSink
import com.ghoststream.core.model.NetworkAvailability
import com.ghoststream.core.model.NetworkType
import com.ghoststream.core.model.NoOpDebugLogSink
import com.ghoststream.core.model.RecentSession
import com.ghoststream.core.model.SessionState
import com.ghoststream.core.model.SharedFolder
import com.ghoststream.core.model.SharedItem
import com.ghoststream.core.model.TransferStats
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class InMemorySessionManager(
    private val debugLogSink: DebugLogSink = NoOpDebugLogSink,
) : SessionManager {
    private val stateLock = Any()
    private val _sessionState = MutableStateFlow(SessionState())
    private val _recentSessions = MutableStateFlow<List<RecentSession>>(emptyList())
    private val authTokens = linkedMapOf<String, String>()

    private var speedWindowStartedAt = 0L
    private var speedWindowBytes = 0L

    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()
    override val recentSessions: StateFlow<List<RecentSession>> = _recentSessions.asStateFlow()

    override fun refreshSelection(items: List<SharedItem>, folders: List<SharedFolder>) {
        _sessionState.update { current ->
            current.copy(selectedItems = items, selectedFolders = folders)
        }
    }

    override fun updateNetworkAvailability(networkAvailability: NetworkAvailability) {
        _sessionState.update { current ->
            current.copy(networkAvailability = networkAvailability)
        }
    }

    override suspend fun startSession(
        port: Int,
        sessionUrl: String,
        hostname: String?,
        items: List<SharedItem>,
        folders: List<SharedFolder>,
        networkAvailability: NetworkAvailability,
        authEnabled: Boolean,
        pin: String?,
    ) {
        debugLogSink.log("SessionManager", "startSession begin port=$port url=$sessionUrl itemCount=${items.size} folderCount=${folders.size}")
        synchronized(stateLock) {
            val now = System.currentTimeMillis()
            speedWindowStartedAt = now
            speedWindowBytes = 0L
            authTokens.clear()
            _sessionState.value = SessionState(
                sessionId = UUID.randomUUID().toString(),
                isSharing = true,
                startedAtEpochMs = now,
                serverPort = port,
                sessionUrl = sessionUrl,
                hostname = hostname,
                advertisedName = null,
                selectedItems = items,
                selectedFolders = folders,
                networkAvailability = networkAvailability,
                authEnabled = authEnabled,
                pin = pin,
                transferStats = TransferStats(
                    startedAtEpochMs = now,
                    lastActivityEpochMs = now,
                ),
                message = "Sharing is live",
            )
        }
        debugLogSink.log("SessionManager", "startSession completed stateSharing=${_sessionState.value.isSharing} stateUrl=${_sessionState.value.sessionUrl}")
    }

    override fun updateAdvertisedAccess(
        advertisedName: String?,
        hostname: String?,
    ) {
        _sessionState.update { current ->
            if (!current.isSharing) {
                current
            } else {
                current.copy(
                    advertisedName = advertisedName ?: current.advertisedName,
                    hostname = hostname ?: current.hostname,
                )
            }
        }
    }

    override suspend fun stopSession(message: String, recordRecentSession: Boolean) {
        debugLogSink.log("SessionManager", "stopSession begin message=$message recordRecentSession=$recordRecentSession")
        synchronized(stateLock) {
            val current = _sessionState.value
            val endedAt = System.currentTimeMillis()
            val sessionId = current.sessionId
            val startedAtEpochMs = current.startedAtEpochMs
            if (recordRecentSession && sessionId != null && startedAtEpochMs != null) {
                _recentSessions.value = listOf(
                    RecentSession(
                        sessionId = sessionId,
                        endedAtEpochMs = endedAt,
                        totalItems = current.selectedItems.size,
                        totalBytesSent = current.transferStats.totalBytesSent,
                        networkType = current.networkAvailability.type,
                    ),
                ) + _recentSessions.value
            }
            authTokens.clear()
            _sessionState.value = SessionState(
                selectedItems = current.selectedItems,
                selectedFolders = current.selectedFolders,
                networkAvailability = current.networkAvailability.copy(
                    type = current.networkAvailability.type.takeUnless { it == NetworkType.NONE }
                        ?: NetworkType.NONE,
                    isReady = current.networkAvailability.isReady,
                    helperText = current.networkAvailability.helperText,
                ),
                message = message,
            )
        }
        debugLogSink.log("SessionManager", "stopSession completed")
    }

    override fun clearRecentSessions() {
        _recentSessions.value = emptyList()
    }

    override suspend fun blockClient(ipAddress: String) {
        synchronized(stateLock) {
            if (_sessionState.value.blockedClients.any { it.ipAddress == ipAddress }) return
            _sessionState.update { current ->
                current.copy(
                    blockedClients = current.blockedClients + BlockedClient(
                        ipAddress = ipAddress,
                        blockedAtEpochMs = System.currentTimeMillis(),
                    ),
                    connectedClients = current.connectedClients.filterNot { it.ipAddress == ipAddress },
                )
            }
            authTokens.entries.removeAll { it.value == ipAddress }
        }
    }

    override suspend fun unblockClient(ipAddress: String) {
        synchronized(stateLock) {
            _sessionState.update { current ->
                current.copy(
                    blockedClients = current.blockedClients.filterNot { it.ipAddress == ipAddress },
                )
            }
        }
    }

    override fun isBlocked(ipAddress: String): Boolean {
        return _sessionState.value.blockedClients.any { it.ipAddress == ipAddress }
    }

    override fun blockedClients(): List<BlockedClient> = _sessionState.value.blockedClients

    override fun observeClient(ipAddress: String, userAgent: String?, activity: ClientActivity) {
        if (isBlocked(ipAddress)) return
        val now = System.currentTimeMillis()
        _sessionState.update { current ->
            val displayName = userAgent?.substringBefore("/")?.takeIf { it.isNotBlank() }
            val existing = current.connectedClients.firstOrNull { it.ipAddress == ipAddress }
            val updated = ConnectedClient(
                id = existing?.id ?: UUID.randomUUID().toString(),
                ipAddress = ipAddress,
                displayName = displayName,
                userAgent = userAgent,
                activity = activity,
                bytesServed = existing?.bytesServed ?: 0L,
                connectedAtEpochMs = existing?.connectedAtEpochMs ?: now,
                lastSeenEpochMs = now,
            )
            current.copy(
                connectedClients = current.connectedClients.filterNot { it.ipAddress == ipAddress } + updated,
                transferStats = current.transferStats.copy(lastActivityEpochMs = now),
            )
        }
    }

    override fun onTransferStarted(ipAddress: String, activity: ClientActivity, isDownload: Boolean) {
        val now = System.currentTimeMillis()
        observeClient(ipAddress = ipAddress, userAgent = null, activity = activity)
        _sessionState.update { current ->
            current.copy(
                transferStats = current.transferStats.copy(
                    activeStreamCount = current.transferStats.activeStreamCount + 1,
                    activeDownloads = current.transferStats.activeDownloads + if (isDownload) 1 else 0,
                    lastActivityEpochMs = now,
                ),
            )
        }
    }

    override fun onTransferProgress(ipAddress: String, bytes: Long, activity: ClientActivity) {
        val now = System.currentTimeMillis()
        if (speedWindowStartedAt == 0L || now - speedWindowStartedAt > 1_000L) {
            speedWindowStartedAt = now
            speedWindowBytes = bytes
        } else {
            speedWindowBytes += bytes
        }

        _sessionState.update { current ->
            val updatedClients = current.connectedClients.map { client ->
                if (client.ipAddress != ipAddress) {
                    client
                } else {
                    client.copy(
                        activity = activity,
                        bytesServed = client.bytesServed + bytes,
                        lastSeenEpochMs = now,
                    )
                }
            }
            current.copy(
                connectedClients = updatedClients,
                transferStats = current.transferStats.copy(
                    totalBytesSent = current.transferStats.totalBytesSent + bytes,
                    currentBytesPerSecond = speedWindowBytes,
                    lastActivityEpochMs = now,
                ),
            )
        }
    }

    override fun onTransferCompleted(ipAddress: String, activity: ClientActivity, wasDownload: Boolean) {
        val now = System.currentTimeMillis()
        _sessionState.update { current ->
            current.copy(
                connectedClients = current.connectedClients.map { client ->
                    if (client.ipAddress != ipAddress) client else client.copy(
                        activity = activity,
                        lastSeenEpochMs = now,
                    )
                },
                transferStats = current.transferStats.copy(
                    activeStreamCount = (current.transferStats.activeStreamCount - 1).coerceAtLeast(0),
                    activeDownloads = (
                        current.transferStats.activeDownloads - if (wasDownload) 1 else 0
                    ).coerceAtLeast(0),
                    completedDownloads = current.transferStats.completedDownloads + if (wasDownload) 1 else 0,
                    lastActivityEpochMs = now,
                ),
            )
        }
    }

    override fun generateToken(ipAddress: String): String {
        val token = UUID.randomUUID().toString()
        authTokens[token] = ipAddress
        return token
    }

    override fun validateToken(token: String?): Boolean {
        return token != null && authTokens.containsKey(token)
    }

    override fun clearBrowserAuth() {
        authTokens.clear()
    }

    override fun isPinValid(pin: String): Boolean {
        val currentPin = _sessionState.value.pin ?: return false
        return currentPin == pin
    }

    override fun regeneratePin(): String {
        val newPin = kotlin.random.Random.nextInt(1000, 9999).toString()
        authTokens.clear()
        _sessionState.update { current ->
            current.copy(pin = newPin)
        }
        return newPin
    }

    override fun disconnectAllClients() {
        val now = System.currentTimeMillis()
        _sessionState.update { current ->
            current.copy(
                connectedClients = emptyList(),
                transferStats = current.transferStats.copy(lastActivityEpochMs = now),
            )
        }
        authTokens.clear()
    }
}
