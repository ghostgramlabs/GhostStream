package com.ghoststream.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class NetworkType {
    WIFI,
    HOTSPOT,
    LOCAL,
    NONE,
}

@Serializable
enum class ClientActivity {
    BROWSING,
    WATCHING_VIDEO,
    DOWNLOADING,
    PLAYING_MUSIC,
    VIEWING_PHOTO,
}

@Serializable
data class ConnectedClient(
    val id: String,
    val ipAddress: String,
    val displayName: String? = null,
    val userAgent: String? = null,
    val activity: ClientActivity = ClientActivity.BROWSING,
    val bytesServed: Long = 0,
    val connectedAtEpochMs: Long,
    val lastSeenEpochMs: Long,
)

@Serializable
data class BlockedClient(
    val ipAddress: String,
    val blockedAtEpochMs: Long,
    val note: String = "Blocked for this session",
)

@Serializable
data class TransferStats(
    val totalBytesSent: Long = 0,
    val currentBytesPerSecond: Long = 0,
    val activeStreamCount: Int = 0,
    val activeDownloads: Int = 0,
    val completedDownloads: Int = 0,
    val startedAtEpochMs: Long? = null,
    val lastActivityEpochMs: Long? = null,
)

@Serializable
data class NetworkAvailability(
    val type: NetworkType,
    val localAddress: String? = null,
    val isReady: Boolean,
    val helperText: String,
)

@Serializable
data class SessionState(
    val sessionId: String? = null,
    val isSharing: Boolean = false,
    val startedAtEpochMs: Long? = null,
    val serverPort: Int? = null,
    val sessionUrl: String? = null,
    val advertisedName: String? = null,
    val networkAvailability: NetworkAvailability = NetworkAvailability(
        type = NetworkType.NONE,
        isReady = false,
        helperText = "Connect both devices to the same Wi-Fi or hotspot.",
    ),
    val selectedItems: List<SharedItem> = emptyList(),
    val selectedFolders: List<SharedFolder> = emptyList(),
    val authEnabled: Boolean = false,
    val pin: String? = null,
    val connectedClients: List<ConnectedClient> = emptyList(),
    val blockedClients: List<BlockedClient> = emptyList(),
    val transferStats: TransferStats = TransferStats(),
    val hostname: String? = null,
    val message: String = "Not sharing",
    val errorMessage: String? = null,
)
