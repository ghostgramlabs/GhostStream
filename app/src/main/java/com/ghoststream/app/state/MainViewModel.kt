package com.ghoststream.app.state

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ghoststream.core.model.AppSettings
import com.ghoststream.core.model.AutoStopOption
import com.ghoststream.core.model.LibraryState
import com.ghoststream.core.model.NearbyDevice
import com.ghoststream.core.model.NearbyDiscoveryState
import com.ghoststream.core.model.SessionState
import com.ghoststream.core.model.SmartSelectionGroup
import com.ghoststream.core.model.RecentSession
import com.ghoststream.core.media.CompatibilityJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val smartGroups = MutableStateFlow(emptyList<SmartSelectionGroup>())
    private val smartGroupsLoading = MutableStateFlow(false)
    private val pendingShareAfterNetworkReady = MutableStateFlow(false)
    private val startSharingInProgress = MutableStateFlow(false)
    private val connectingNearbyDeviceId = MutableStateFlow<String?>(null)
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
        container.nsdDiscoveryManager.discoveryState,
        pendingShareAfterNetworkReady,
        startSharingInProgress,
        connectingNearbyDeviceId,
    ) { values ->
        val settings = values[0] as AppSettings
        val library = values[1] as LibraryState
        val session = values[2] as SessionState
        val recent = values[3] as List<RecentSession>
        val groups = values[4] as List<SmartSelectionGroup>
        val loading = values[5] as Boolean
        val compatibilityJobs = values[6] as Map<String, CompatibilityJob>
        val nearbyDiscoveryState = values[7] as NearbyDiscoveryState
        val pendingShare = values[8] as Boolean
        val isStartingShare = values[9] as Boolean
        val connectingNearbyId = values[10] as String?
        MainUiState(
            isReady = true,
            settings = settings,
            libraryState = library,
            sessionState = session,
            recentSessions = recent,
            smartGroups = groups,
            smartGroupsLoading = loading,
            compatibilityJobs = compatibilityJobs,
            nearbyDiscoveryState = nearbyDiscoveryState,
            pendingShareAfterNetworkReady = pendingShare,
            isStartingShare = isStartingShare,
            connectingNearbyDeviceId = connectingNearbyId,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    init {
        refreshNetwork()
        container.debugLogRepository.log("MainViewModel", "initialized")
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            container.settingsRepository.markOnboardingCompleted()
            _events.emit(AppEvent.NavigateHome)
        }
    }

    fun refreshNetwork() {
        viewModelScope.launch {
            container.debugLogRepository.log("MainViewModel", "refreshNetwork requested")
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
                container.debugLogRepository.log("MainViewModel", "requestStartSharing ignored because session already sharing")
                _events.emit(AppEvent.NavigateSession)
                return@launch
            }

            container.debugLogRepository.log("MainViewModel", "requestStartSharing started")
            startSharingInProgress.value = true
            try {
                when (val result = withContext(Dispatchers.IO) { container.sharingCoordinator.preflight() }) {
                    SharePreflightResult.NoContent -> {
                        container.debugLogRepository.log("MainViewModel", "preflight result: no content")
                        pendingShareAfterNetworkReady.value = false
                        startSharingInProgress.value = false
                        _events.emit(
                            AppEvent.ShowMessage("Add some content first to start sharing."),
                        )
                    }

                    is SharePreflightResult.NeedsNetwork -> {
                        container.debugLogRepository.log(
                            "MainViewModel",
                            "preflight result: needs network type=${result.availability.type} localAddress=${result.availability.localAddress}",
                        )
                        pendingShareAfterNetworkReady.value = true
                        startSharingInProgress.value = false
                        _events.emit(AppEvent.NavigateNetworkSetup)
                    }

                    is SharePreflightResult.Failure -> {
                        container.debugLogRepository.log("MainViewModel", "preflight result: failure message=${result.message}")
                        pendingShareAfterNetworkReady.value = false
                        startSharingInProgress.value = false
                        _events.emit(AppEvent.ShowMessage(result.message))
                    }

                    SharePreflightResult.Ready -> {
                        container.debugLogRepository.log("MainViewModel", "preflight result: ready")
                        pendingShareAfterNetworkReady.value = false
                        startSharingAfterReadyCheck()
                    }
                }
            } catch (e: Exception) {
                container.debugLogRepository.log("MainViewModel", "requestStartSharing crashed", e)
                pendingShareAfterNetworkReady.value = false
                startSharingInProgress.value = false
                _events.emit(AppEvent.ShowMessage("Something went wrong. Please try again."))
            }
        }
    }

    fun resumePendingShareAfterNetworkReady() {
        viewModelScope.launch {
            if (!pendingShareAfterNetworkReady.value || startSharingInProgress.value) return@launch
            container.debugLogRepository.log("MainViewModel", "resumePendingShareAfterNetworkReady")
            startSharingInProgress.value = true
            startSharingAfterReadyCheck()
        }
    }

    fun requestStopSharing() {
        viewModelScope.launch {
            container.debugLogRepository.log("MainViewModel", "requestStopSharing")
            pendingShareAfterNetworkReady.value = false
            startSharingInProgress.value = false
            container.sharingCoordinator.stopSharing()
            _events.emit(AppEvent.StopSharingService)
            _events.emit(AppEvent.NavigateHome)
        }
    }

    fun onServiceStartFailure(message: String) {
        viewModelScope.launch {
            container.debugLogRepository.log("MainViewModel", "service start failure message=$message")
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

    fun regeneratePin() {
        container.sessionManager.regeneratePin()
    }

    fun disconnectAll() {
        container.sessionManager.disconnectAllClients()
    }

    fun debugLogLocationDescription(): String = container.debugLogRepository.locationDescription()

    fun shareDebugLog() {
        if (!container.debugLogRepository.isEnabled()) return
        viewModelScope.launch {
            container.debugLogRepository.log("MainViewModel", "shareDebugLog requested")
            container.debugLogRepository.shareableUri()
                .onSuccess { uri -> _events.emit(AppEvent.ShareDebugLog(uri)) }
                .onFailure {
                    container.debugLogRepository.log("MainViewModel", "shareDebugLog failed", it)
                    _events.emit(AppEvent.ShowMessage("Unable to prepare the debug log right now."))
                }
        }
    }

    fun clearDebugLog() {
        if (!container.debugLogRepository.isEnabled()) return
        viewModelScope.launch {
            container.debugLogRepository.clear()
                .onSuccess { _events.emit(AppEvent.ShowMessage("Debug log cleared.")) }
                .onFailure {
                    container.debugLogRepository.log("MainViewModel", "clearDebugLog failed", it)
                    _events.emit(AppEvent.ShowMessage("Unable to clear the debug log right now."))
                }
        }
    }

    fun startNearbyDiscovery() {
        container.debugLogRepository.log("MainViewModel", "startNearbyDiscovery")
        container.nsdDiscoveryManager.start()
    }

    fun stopNearbyDiscovery() {
        container.debugLogRepository.log("MainViewModel", "stopNearbyDiscovery")
        container.nsdDiscoveryManager.stop()
    }

    fun openNearbyDevice(device: NearbyDevice) {
        viewModelScope.launch {
            connectingNearbyDeviceId.value = device.id
            container.debugLogRepository.log(
                "MainViewModel",
                "openNearbyDevice id=${device.id} serviceName=${device.serviceName} launchUrl=${device.launchUrl}",
            )
            _events.emit(AppEvent.OpenExternalUrl(device.launchUrl))
            connectingNearbyDeviceId.value = null
        }
    }

    private suspend fun startSharingAfterReadyCheck() {
        container.debugLogRepository.log("MainViewModel", "startSharingAfterReadyCheck")
        when (val startResult = withContext(Dispatchers.IO) {
            container.sharingCoordinator.beginSharing(assumePreflightReady = true)
        }) {
            is ShareStartResult.Started -> {
                container.debugLogRepository.log("MainViewModel", "share started url=${startResult.url}")
                pendingShareAfterNetworkReady.value = false
                startSharingInProgress.value = false
                container.debugLogRepository.log("MainViewModel", "emitting StartSharingService")
                _events.emit(AppEvent.StartSharingService)
                container.debugLogRepository.log("MainViewModel", "emitting NavigateSession")
                _events.emit(AppEvent.NavigateSession)
            }

            is ShareStartResult.Failure -> {
                container.debugLogRepository.log("MainViewModel", "share failed message=${startResult.message}")
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
