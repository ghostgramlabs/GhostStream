package com.ghostgramlabs.directserve.state

import android.app.Application
import android.net.Uri
import com.ghostgramlabs.directserve.BuildConfig
import com.ghostgramlabs.directserve.core.resources.R
import com.ghostgramlabs.directserve.localization.AppLanguages
import com.ghostgramlabs.directserve.localization.LocaleManager
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
import com.ghoststream.core.model.buildConnectionDiagnostics
import com.ghoststream.core.media.CompatibilityJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(
    private val application: Application,
    private val container: AppContainer,
) : ViewModel() {

    private val smartGroups = MutableStateFlow(emptyList<SmartSelectionGroup>())
    private val smartGroupsLoading = MutableStateFlow(false)
    private val pendingShareAfterNetworkReady = MutableStateFlow(false)
    private val startSharingInProgress = MutableStateFlow(false)
    private val libraryImportingCount = MutableStateFlow(0)
    private val connectingNearbyDeviceId = MutableStateFlow<String?>(null)
    private val _browserPrepManuallyTriggered = MutableStateFlow(false)
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
        libraryImportingCount,
        connectingNearbyDeviceId,
        container.sessionManager.pendingUploadRequest,
        container.historyRepository.allHistory,
        _browserPrepManuallyTriggered,
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
        val importingCount = values[10] as Int
        val connectingNearbyId = values[11] as String?
        val pendingUpload = values[12] as com.ghoststream.core.model.UploadRequest?
        val history = values[13] as List<com.ghoststream.core.model.TransferRecord>
        val manuallyTriggered = values[14] as Boolean
        val filteredNearbyDiscoveryState = nearbyDiscoveryState.filterCurrentSession(session, application)
        MainUiState(
            isReady = true,
            settings = settings,
            libraryState = library,
            sessionState = session,
            recentSessions = if (settings.showRecentSessions) recent else emptyList(),
            smartGroups = groups,
            smartGroupsLoading = loading,
            compatibilityJobs = compatibilityJobs,
            connectionDiagnostics = buildConnectionDiagnostics(
                libraryState = library,
                sessionState = session,
                nearbyDiscoveryState = filteredNearbyDiscoveryState,
            ),
            nearbyDiscoveryState = filteredNearbyDiscoveryState,
            pendingShareAfterNetworkReady = pendingShare,
            isStartingShare = isStartingShare,
            libraryImportingCount = importingCount,
            connectingNearbyDeviceId = connectingNearbyId,
            pendingUploadRequest = pendingUpload,
            transferHistory = history,
            browserPrepManuallyTriggered = manuallyTriggered,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    init {
        refreshNetwork()
        container.debugLogRepository.log("MainViewModel", "initialized")
        // Auto-reset the manual trigger flag once all queued/active compatibility jobs finish.
        container.compatibilityPipeline.jobs
            .onEach { jobs ->
                if (_browserPrepManuallyTriggered.value) {
                    val hasActiveJobs = jobs.values.any { job ->
                        job.status == com.ghoststream.core.media.CompatibilityStatus.QUEUED ||
                            job.status == com.ghoststream.core.media.CompatibilityStatus.PREPARING
                    }
                    if (!hasActiveJobs) {
                        _browserPrepManuallyTriggered.value = false
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            container.settingsRepository.markOnboardingCompleted()
            _events.emit(AppEvent.NavigateHome)
        }
    }

    fun selectLanguage(languageTag: String, completeSelection: Boolean) {
        val canonicalTag = AppLanguages.canonicalize(languageTag)
        LocaleManager.applyLanguageTag(canonicalTag)
        viewModelScope.launch {
            if (completeSelection) {
                container.settingsRepository.completeLanguageSelection(canonicalTag)
            } else {
                container.settingsRepository.update { current ->
                    current.copy(languageTag = canonicalTag)
                }
            }
        }
    }

    fun detectInitialLanguageTag(): String {
        val stored = uiState.value.settings.languageTag
        return stored ?: AppLanguages.detectSupportedDeviceLanguage().tag
    }

    fun versionLabel(): String = BuildConfig.VERSION_NAME

    fun refreshNetwork() {
        viewModelScope.launch {
            container.debugLogRepository.log("MainViewModel", "refreshNetwork requested")
            container.sessionManager.updateNetworkAvailability(container.networkInspector.inspect())
        }
    }

    fun loadSmartGroups() {
        viewModelScope.launch {
            if (smartGroupsLoading.value) return@launch
            smartGroupsLoading.value = true
            container.debugLogRepository.log("MainViewModel", "loadSmartGroups started")
            runCatching {
                container.storageRepository.loadSmartSelectionGroups()
            }.onSuccess { groups ->
                smartGroups.value = groups
                container.debugLogRepository.log("MainViewModel", "loadSmartGroups completed count=${groups.size}")
            }.onFailure { error ->
                smartGroups.value = emptyList()
                container.debugLogRepository.log("MainViewModel", "loadSmartGroups failed", error)
            }
            smartGroupsLoading.value = false
        }
    }

    fun addFiles(uris: List<Uri>) {
        viewModelScope.launch {
            libraryImportingCount.value += uris.size
            try {
                container.storageRepository.addFiles(uris)
            } finally {
                libraryImportingCount.value = (libraryImportingCount.value - uris.size).coerceAtLeast(0)
            }
        }
    }

    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            libraryImportingCount.value += 1
            try {
                val result = container.storageRepository.addFolder(uri)
                result.exceptionOrNull()?.let {
                    _events.emit(AppEvent.ShowMessage(application.getString(R.string.message_unable_add_folder)))
                }
            } finally {
                libraryImportingCount.value = (libraryImportingCount.value - 1).coerceAtLeast(0)
            }
        }
    }

    fun addSmartSelection(uris: List<Uri>) {
        viewModelScope.launch {
            libraryImportingCount.value += uris.size
            try {
                container.storageRepository.addSmartSelection(uris)
            } finally {
                libraryImportingCount.value = (libraryImportingCount.value - uris.size).coerceAtLeast(0)
            }
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

    fun clearLibrarySelection() {
        viewModelScope.launch {
            container.storageRepository.clearSelection()
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
                        container.debugLogRepository.log("MainViewModel", "preflight result: no content (allowing for receiving)")
                        pendingShareAfterNetworkReady.value = false
                        startSharingAfterReadyCheck()
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
                _events.emit(AppEvent.ShowMessage(application.getString(R.string.message_something_went_wrong)))
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
            val current = container.settingsRepository.settings.first()
            val updated = transform(current)
            container.settingsRepository.update { updated }
            if (current.showRecentSessions && !updated.showRecentSessions) {
                container.sessionManager.clearRecentSessions()
            }
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

    fun updateSessionPinProtection(enabled: Boolean) {
        viewModelScope.launch {
            val current = container.settingsRepository.settings.first()
            container.settingsRepository.update { current.copy(requireSessionPin = enabled) }
            container.sharingCoordinator.updatePinProtection(enabled)
            _events.emit(
                AppEvent.ShowMessage(
                    if (enabled) application.getString(R.string.message_pin_on) else application.getString(R.string.message_pin_off)
                )
            )
        }
    }

    fun disconnectAll() {
        container.sessionManager.disconnectAllClients()
    }

    fun resolveUploadRequest(requestId: String, accepted: Boolean) {
        container.sessionManager.resolveUploadRequest(requestId, accepted)
    }

    fun navigateToHistory() {
        viewModelScope.launch {
            _events.emit(AppEvent.NavigateHistory)
        }
    }

    fun openReceivedFile(fileUri: String) {
        viewModelScope.launch {
            _events.emit(AppEvent.OpenFile(fileUri))
        }
    }

    fun deleteHistoryRecord(id: String) {
        viewModelScope.launch {
            container.historyRepository.deleteRecord(id)
        }
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
                    _events.emit(AppEvent.ShowMessage(application.getString(R.string.message_debug_log_prepare_failed)))
                }
        }
    }

    fun clearDebugLog() {
        if (!container.debugLogRepository.isEnabled()) return
        viewModelScope.launch {
            container.debugLogRepository.clear()
                .onSuccess { _events.emit(AppEvent.ShowMessage(application.getString(R.string.message_debug_log_cleared))) }
                .onFailure {
                    container.debugLogRepository.log("MainViewModel", "clearDebugLog failed", it)
                    _events.emit(AppEvent.ShowMessage(application.getString(R.string.message_debug_log_clear_failed)))
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

    fun refreshNearbyDiscovery() {
        container.debugLogRepository.log("MainViewModel", "refreshNearbyDiscovery")
        container.nsdDiscoveryManager.refresh()
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

    fun requestPrepareItem(itemId: String) {
        viewModelScope.launch {
            val item = container.storageRepository.findItemById(itemId)
            if (item == null) {
                _events.emit(AppEvent.ShowMessage(application.getString(R.string.message_file_no_longer_available)))
                return@launch
            }
            container.compatibilityPipeline.requestPreparation(item, prioritize = true)
            _events.emit(AppEvent.ShowMessage(application.getString(R.string.message_preparing_browser_playback, item.displayName)))
        }
    }

    fun prepareLiveBrowserFiles() {
        viewModelScope.launch {
            val session = container.sessionManager.sessionState.value
            if (!session.isSharing) {
                _events.emit(AppEvent.ShowMessage(application.getString(R.string.main_not_sharing)))
                return@launch
            }

            val queuedCount = queueSessionBrowserPlayback()
            val message = if (queuedCount > 0) {
                _browserPrepManuallyTriggered.value = true
                application.getString(R.string.message_browser_playback_queued_all, queuedCount)
            } else {
                application.getString(R.string.message_browser_playback_already_ready)
            }
            _events.emit(AppEvent.ShowMessage(message))
        }
    }

    fun stopLiveBrowserFiles() {
        viewModelScope.launch {
            _browserPrepManuallyTriggered.value = false
            container.compatibilityPipeline.cancelPendingPreparations()
            _events.emit(AppEvent.ShowMessage(application.getString(R.string.message_browser_playback_stopped)))
        }
    }

    fun refreshLiveLibrary() {
        viewModelScope.launch {
            val session = container.sessionManager.sessionState.value
            if (!session.isSharing) {
                _events.emit(AppEvent.ShowMessage(application.getString(R.string.main_not_sharing)))
                return@launch
            }

            val library = container.storageRepository.libraryState.value
            val previousItemIds = session.selectedItems.mapTo(mutableSetOf()) { it.id }
            val previousFolderIds = session.selectedFolders.mapTo(mutableSetOf()) { it.id }
            val newItemCount = library.items.count { it.id !in previousItemIds }
            val newFolderCount = library.folders.count { it.id !in previousFolderIds }

            container.sessionManager.refreshSelection(library.items, library.folders)

            val message = if (newItemCount > 0 || newFolderCount > 0) {
                application.getString(
                    R.string.message_live_library_refreshed_updates,
                    newItemCount,
                    newFolderCount,
                )
            } else {
                application.getString(R.string.message_live_library_refreshed_current)
            }
            _events.emit(AppEvent.ShowMessage(message))
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

    private suspend fun queueSessionBrowserPlayback(): Int {
        val session = container.sessionManager.sessionState.value
        if (!session.isSharing) return 0

        var queuedCount = 0
        session.selectedItems
            .filter { item -> item.playbackDecision.mode != com.ghoststream.core.model.PlaybackMode.DIRECT }
            .forEach { item ->
                val existing = container.compatibilityPipeline.currentJob(item.id)
                val alreadyHandled = existing?.status == com.ghoststream.core.media.CompatibilityStatus.READY ||
                    existing?.status == com.ghoststream.core.media.CompatibilityStatus.QUEUED ||
                    existing?.status == com.ghoststream.core.media.CompatibilityStatus.PREPARING
                if (!alreadyHandled) {
                    container.compatibilityPipeline.requestPreparation(item, prioritize = false)
                    queuedCount += 1
                }
            }
        return queuedCount
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val application = container.application
                    return MainViewModel(application, container) as T
                }
            }
        }
    }
}

private fun NearbyDiscoveryState.filterCurrentSession(sessionState: SessionState, application: Application): NearbyDiscoveryState {
    val sessionHost = sessionState.sessionUrl?.let(Uri::parse)?.host?.lowercase()
    val sessionAddress = sessionState.networkAvailability.localAddress?.lowercase()
    val sessionHostname = sessionState.hostname?.lowercase()
    val sessionPort = sessionState.serverPort
    val sessionId = sessionState.sessionId

    val filteredDevices = devices.filterNot { device ->
        val sameSessionId = sessionId != null && device.sessionId == sessionId
        val sameAddress = sessionAddress != null && device.address.lowercase() == sessionAddress
        val sameHost = listOfNotNull(device.hostname?.lowercase(), device.friendlyUrl?.let(Uri::parse)?.host?.lowercase())
            .any { it == sessionHost || it == sessionHostname }
        val samePort = sessionPort != null && device.port == sessionPort
        sameSessionId || (samePort && (sameAddress || sameHost))
    }

    return copy(
        devices = filteredDevices,
        helperText = when {
            lastError != null -> helperText
            filteredDevices.isNotEmpty() -> application.getString(R.string.nearby_helper_tap_session)
            else -> application.getString(R.string.nearby_helper_open_on_other_device)
        },
    )
}
