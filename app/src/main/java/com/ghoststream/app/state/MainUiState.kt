package com.ghostgramlabs.directserve.state

import android.net.Uri
import com.ghoststream.core.media.CompatibilityJob
import com.ghoststream.core.model.AppSettings
import com.ghoststream.core.model.ConnectionDiagnostics
import com.ghoststream.core.model.LibraryState
import com.ghoststream.core.model.NearbyDiscoveryState
import com.ghoststream.core.model.RecentSession
import com.ghoststream.core.model.SessionState
import com.ghoststream.core.model.SmartSelectionGroup
import com.ghoststream.core.model.TransferRecord

data class MainUiState(
    val isReady: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val libraryState: LibraryState = LibraryState(),
    val sessionState: SessionState = SessionState(),
    val recentSessions: List<RecentSession> = emptyList(),
    val smartGroups: List<SmartSelectionGroup> = emptyList(),
    val smartGroupsLoading: Boolean = false,
    val compatibilityJobs: Map<String, CompatibilityJob> = emptyMap(),
    val connectionDiagnostics: ConnectionDiagnostics = ConnectionDiagnostics(summary = "", checks = emptyList()),
    val nearbyDiscoveryState: NearbyDiscoveryState = NearbyDiscoveryState(),
    val pendingShareAfterNetworkReady: Boolean = false,
    val isStartingShare: Boolean = false,
    val connectingNearbyDeviceId: String? = null,
    val pendingUploadRequest: com.ghoststream.core.model.UploadRequest? = null,
    val transferHistory: List<TransferRecord> = emptyList(),
)

sealed interface AppEvent {
    data class ShowMessage(val message: String) : AppEvent
    data object NavigateNetworkSetup : AppEvent
    data object NavigateSession : AppEvent
    data object NavigateHome : AppEvent
    data object NavigateLibrary : AppEvent
    data object StartSharingService : AppEvent
    data object StopSharingService : AppEvent
    data class ShareDebugLog(val uri: Uri) : AppEvent
    data class OpenExternalUrl(val url: String) : AppEvent
    data object NavigateHistory : AppEvent
    data class OpenFile(val fileUri: String) : AppEvent
}
