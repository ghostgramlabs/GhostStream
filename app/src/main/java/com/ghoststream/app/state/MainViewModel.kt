package com.ghoststream.app.state

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ghoststream.core.model.AppSettings
import com.ghoststream.core.model.AutoStopOption
import com.ghoststream.core.model.LibraryState
import com.ghoststream.core.model.SessionState
import com.ghoststream.core.model.SmartSelectionGroup
import com.ghoststream.core.model.RecentSession
import com.ghoststream.core.media.CompatibilityJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val smartGroups = MutableStateFlow(emptyList<SmartSelectionGroup>())
    private val smartGroupsLoading = MutableStateFlow(false)
    private val pendingShareAfterNetworkReady = MutableStateFlow(false)
    private val startSharingInProgress = MutableStateFlow(false)
    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 8)

    val events = _events.asSharedFlow()

    val uiState: StateFlow<MainUiState> = combine(
        container.settingsRepository.settings,
        container.storageRepository.libraryState,
        container.sessionManager.sessionState,
        container.sessionManager.recentSessions,
        smartGroups,
        smartGroupsLoading,
        container.compatibilityPipeline.jobs,
        pendingShareAfterNetworkReady,
        startSharingInProgress,
    ) { values ->
        val settings = values[0] as AppSettings
        val library = values[1] as LibraryState
        val session = values[2] as SessionState
        val recent = values[3] as List<RecentSession>
        val groups = values[4] as List<SmartSelectionGroup>
        val loading = values[5] as Boolean
        val compatibilityJobs = values[6] as Map<String, CompatibilityJob>
        val pendingShare = values[7] as Boolean
        val isStartingShare = values[8] as Boolean
        MainUiState(
            isReady = true,
            settings = settings,
            libraryState = library,
            sessionState = session,
            recentSessions = recent,
            smartGroups = groups,
            smartGroupsLoading = loading,
            compatibilityJobs = compatibilityJobs,
            pendingShareAfterNetworkReady = pendingShare,
            isStartingShare = isStartingShare,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    init {
        refreshNetwork()
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            container.settingsRepository.markOnboardingCompleted()
            _events.emit(AppEvent.NavigateHome)
        }
    }

    fun refreshNetwork() {
        viewModelScope.launch {
            container.sessionManager.updateNetworkAvailability(container.networkInspector.inspect())
        }
    }

    fun loadSmartGroups() {
        viewModelScope.launch {
            smartGroupsLoading.value = true
            smartGroups.value = container.storageRepository.loadSmartSelectionGroups()
            smartGroupsLoading.value = false
        }
    }

    fun addFiles(uris: List<Uri>) {
        viewModelScope.launch {
            container.storageRepository.addFiles(uris)
        }
    }

    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            val result = container.storageRepository.addFolder(uri)
            result.exceptionOrNull()?.let {
                _events.emit(AppEvent.ShowMessage("Unable to add that folder right now."))
            }
        }
    }

    fun addSmartSelection(uris: List<Uri>) {
        viewModelScope.launch {
            container.storageRepository.addSmartSelection(uris)
        }
    }

    fun removeItem(itemId: String) {
        viewModelScope.launch {
            container.storageRepository.removeItem(itemId)
        }
    }

    fun removeFolder(folderId: String) {
        viewModelScope.launch {
            container.storageRepository.removeFolder(folderId)
        }
    }

    fun requestStartSharing() {
        viewModelScope.launch {
            if (startSharingInProgress.value) return@launch
            if (container.sessionManager.sessionState.value.isSharing) {
                _events.emit(AppEvent.NavigateSession)
                return@launch
            }

            startSharingInProgress.value = true
            when (val result = container.sharingCoordinator.preflight()) {
                SharePreflightResult.NoContent -> _events.emit(
                    AppEvent.ShowMessage("Add some content first to start sharing."),
                ).also {
                    pendingShareAfterNetworkReady.value = false
                    startSharingInProgress.value = false
                }

                is SharePreflightResult.NeedsNetwork -> {
                    pendingShareAfterNetworkReady.value = true
                    startSharingInProgress.value = false
                    _events.emit(AppEvent.NavigateNetworkSetup)
                }

                is SharePreflightResult.Failure -> {
                    pendingShareAfterNetworkReady.value = false
                    startSharingInProgress.value = false
                    _events.emit(AppEvent.ShowMessage(result.message))
                }

                SharePreflightResult.Ready -> {
                    pendingShareAfterNetworkReady.value = false
                    startSharingAfterReadyCheck()
                }
            }
        }
    }

    fun resumePendingShareAfterNetworkReady() {
        viewModelScope.launch {
            if (!pendingShareAfterNetworkReady.value || startSharingInProgress.value) return@launch
            startSharingInProgress.value = true
            startSharingAfterReadyCheck()
        }
    }

    fun requestStopSharing() {
        viewModelScope.launch {
            pendingShareAfterNetworkReady.value = false
            startSharingInProgress.value = false
            _events.emit(AppEvent.StopSharingService)
            _events.emit(AppEvent.NavigateHome)
        }
    }

    fun onServiceStartFailure(message: String) {
        viewModelScope.launch {
            _events.emit(AppEvent.ShowMessage(message))
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            container.settingsRepository.update(transform)
        }
    }

    fun updateAutoStop(autoStopOption: AutoStopOption) {
        updateSettings { it.copy(autoStop = autoStopOption) }
    }

    fun blockClient(ipAddress: String) {
        viewModelScope.launch {
            container.sessionManager.blockClient(ipAddress)
        }
    }

    fun unblockClient(ipAddress: String) {
        viewModelScope.launch {
            container.sessionManager.unblockClient(ipAddress)
        }
    }

    private suspend fun startSharingAfterReadyCheck() {
        when (val startResult = container.sharingCoordinator.beginSharing(assumePreflightReady = true)) {
            is ShareStartResult.Started -> {
                pendingShareAfterNetworkReady.value = false
                startSharingInProgress.value = false
                _events.emit(AppEvent.StartSharingService)
                _events.emit(AppEvent.NavigateSession)
            }

            is ShareStartResult.Failure -> {
                pendingShareAfterNetworkReady.value = false
                startSharingInProgress.value = false
                _events.emit(AppEvent.ShowMessage(startResult.message))
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(container) as T
                }
            }
        }
    }
}
