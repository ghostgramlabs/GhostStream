package com.ghoststream.app.state

import com.ghoststream.core.media.CompatibilityJob
import com.ghoststream.core.model.AppSettings
import com.ghoststream.core.model.LibraryState
import com.ghoststream.core.model.RecentSession
import com.ghoststream.core.model.SessionState
import com.ghoststream.core.model.SmartSelectionGroup

data class MainUiState(
    val isReady: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val libraryState: LibraryState = LibraryState(),
    val sessionState: SessionState = SessionState(),
    val recentSessions: List<RecentSession> = emptyList(),
    val smartGroups: List<SmartSelectionGroup> = emptyList(),
    val smartGroupsLoading: Boolean = false,
    val compatibilityJobs: Map<String, CompatibilityJob> = emptyMap(),
    val pendingShareAfterNetworkReady: Boolean = false,
    val isStartingShare: Boolean = false,
)

sealed interface AppEvent {
    data class ShowMessage(val message: String) : AppEvent
    data object NavigateNetworkSetup : AppEvent
    data object NavigateSession : AppEvent
    data object NavigateHome : AppEvent
    data object StartSharingService : AppEvent
    data object StopSharingService : AppEvent
}
