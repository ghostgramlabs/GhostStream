package com.ghostgramlabs.directserve.state

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
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
import com.ghoststream.core.model.LiveAudioStatus
import com.ghoststream.core.model.LiveScreenStatus
import com.ghoststream.core.model.NearbyDevice
import com.ghoststream.core.model.NearbyDiscoveryState
import com.ghoststream.core.model.QUICK_TEXT_PHONE_ID
import com.ghoststream.core.model.QuickTextMessage
import com.ghoststream.core.model.QuickTextTargetType
import com.ghoststream.core.model.SessionState
import com.ghoststream.core.model.SmartSelectionGroup
import com.ghoststream.core.model.RecentSession
import com.ghoststream.core.model.buildConnectionDiagnostics
import com.ghoststream.core.model.displayDeviceName
import com.ghoststream.core.media.CompatibilityJob
import com.ghoststream.core.media.JobPriority
import com.ghoststream.core.network.server.DlnaAnnouncer
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

    private var networkRefreshJob: kotlinx.coroutines.Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { scheduleNetworkRefresh() }
        override fun onLost(network: Network) { scheduleNetworkRefresh() }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { scheduleNetworkRefresh() }
    }

    private fun scheduleNetworkRefresh() {
        networkRefreshJob?.cancel()
        networkRefreshJob = viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            refreshNetwork()
        }
    }

    private val smartGroups = MutableStateFlow(emptyList<SmartSelectionGroup>())
    private val smartGroupsLoading = MutableStateFlow(false)
    private val pendingShareAfterNetworkReady = MutableStateFlow(false)
    private val startSharingInProgress = MutableStateFlow(false)
    private val libraryImportingCount = MutableStateFlow(0)
    private val connectingNearbyDeviceId = MutableStateFlow<String?>(null)
    private val _browserPrepManuallyTriggered = MutableStateFlow(false)
    private val _hasAllFilesAccess = MutableStateFlow(false)
    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 8)
    private var dlnaAnnouncer: DlnaAnnouncer? = null


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
        container.historyRepository.quickTextMessages,
        container.liveScreenManager.state,
        _browserPrepManuallyTriggered,
        _hasAllFilesAccess,
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
        val quickTextHistory = values[14] as List<QuickTextMessage>
        val liveScreenState = values[15] as com.ghoststream.core.model.LiveScreenSessionState
        val manuallyTriggered = values[16] as Boolean
        val allFilesAccess = values[17] as Boolean
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
            quickTextHistory = quickTextHistory,
            liveScreenState = liveScreenState,
            browserPrepManuallyTriggered = manuallyTriggered,
            hasAllFilesAccess = allFilesAccess,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    init {
        refreshNetwork()
        runCatching {
            val cm = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(networkCallback)
        }
        container.debugLogRepository.log("MainViewModel", "initialized")
        container.compatibilityPipeline.jobs
            .onEach { jobs ->
                if (_browserPrepManuallyTriggered.value) {
                    val hasActiveJobs = jobs.values.any { job ->
                        job.status == com.ghoststream.core.media.CompatibilityStatus.QUEUED ||
                            job.status == com.ghoststream.core.media.CompatibilityStatus.PREPARING ||
                            job.status == com.ghoststream.core.media.CompatibilityStatus.ANALYZING ||
                            job.status == com.ghoststream.core.media.CompatibilityStatus.FINALIZING
                    }
                    if (!hasActiveJobs) {
                        _browserPrepManuallyTriggered.value = false
                    }
                }
            }
            .launchIn(viewModelScope)

        combine(
            container.settingsRepository.settings,
            container.sessionManager.sessionState,
        ) { settings, session ->
            syncDlnaAnnouncer(settings.dlnaEnabled, session)
        }.launchIn(viewModelScope)
    }

    fun refreshAllFilesAccessStatus() {
        _hasAllFilesAccess.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun requestAllFilesAccess() {
        viewModelScope.launch {
            _events.emit(AppEvent.RequestAllFilesAccess)
        }
    }

    fun showMessage(message: String) {
        viewModelScope.launch {
            _events.emit(AppEvent.ShowMessage(message))
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            container.settingsRepository.markOnboardingCompleted()
        }
    }

    fun selectLanguage(languageTag: String, completeSelection: Boolean) {
        val canonicalTag = AppLanguages.canonicalize(languageTag)
        if (completeSelection) {
            // Persist BEFORE applying the locale, because
            // AppCompatDelegate.setApplicationLocales() immediately
            // recreates the Activity.  If the DataStore write hasn't
            // finished by then, languageSelectionCompleted is still
            // false and the user sees the language screen a second time.
            viewModelScope.launch {
                container.settingsRepository.completeLanguageSelection(canonicalTag)
                LocaleManager.applyLanguageTag(canonicalTag)
            }
        } else {
            LocaleManager.applyLanguageTag(canonicalTag)
            viewModelScope.launch {
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
            container.settingsRepository.update { current ->
                current.copy(mediaServerMode = false)
            }
            container.storageRepository.clearSelection()
        }
    }

    fun importAllMedia() {
        viewModelScope.launch {
            try {
                libraryImportingCount.value += 1
                container.storageRepository.loadAllDeviceMedia()
                container.settingsRepository.update { current ->
                    current.copy(mediaServerMode = true)
                }
            } finally {
                libraryImportingCount.value = (libraryImportingCount.value - 1).coerceAtLeast(0)
            }
        }
    }

    fun clearAllMedia() {
        viewModelScope.launch {
            container.settingsRepository.update { current ->
                current.copy(mediaServerMode = false)
            }
            container.storageRepository.clearSelection()
        }
    }


    fun requestStartSharing() {
        viewModelScope.launch {
            if (startSharingInProgress.value) return@launch
            if (container.liveScreenManager.state.value.isActive) {
                container.debugLogRepository.log("MainViewModel", "requestStartSharing blocked because live screen is active")
                pendingShareAfterNetworkReady.value = false
                _events.emit(AppEvent.ShowMessage(application.getString(R.string.sharing_blocked_by_live_screen)))
                return@launch
            }
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
                        maybeRequestBatteryOptimizationExemption()
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
                        maybeRequestBatteryOptimizationExemption()
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
            if (container.liveScreenManager.state.value.isActive) {
                container.debugLogRepository.log("MainViewModel", "resumePendingShareAfterNetworkReady blocked because live screen is active")
                pendingShareAfterNetworkReady.value = false
                startSharingInProgress.value = false
                _events.emit(AppEvent.ShowMessage(application.getString(R.string.sharing_blocked_by_live_screen)))
                return@launch
            }
            container.debugLogRepository.log("MainViewModel", "resumePendingShareAfterNetworkReady")
            startSharingInProgress.value = true
            maybeRequestBatteryOptimizationExemption()
            startSharingAfterReadyCheck()
        }
    }

    private suspend fun maybeRequestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val settings = container.settingsRepository.settings.first()
        if (settings.batteryOptimizationPromptShown) return
        val powerManager = application.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(application.packageName)) return

        container.settingsRepository.update { current ->
            current.copy(batteryOptimizationPromptShown = true)
        }
        container.debugLogRepository.log("MainViewModel", "requesting battery optimization exemption")
        _events.emit(AppEvent.RequestBatteryOptimizationExemption)
    }

    fun requestStopSharing() {
        viewModelScope.launch {
            container.debugLogRepository.log("MainViewModel", "requestStopSharing")
            dlnaAnnouncer?.stop()
            dlnaAnnouncer = null
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

    fun updateLiveScreenPinProtection(enabled: Boolean) {
        viewModelScope.launch {
            val current = container.settingsRepository.settings.first()
            container.settingsRepository.update { current.copy(requireSessionPin = enabled) }
            val newPin = if (enabled) generateLivePin(current) else null
            container.liveScreenManager.updateState { it.copy(pin = newPin) }
        }
    }

    fun regenerateLiveScreenPin() {
        viewModelScope.launch {
            val current = container.settingsRepository.settings.first()
            val enabled = current.requireSessionPin
            val effective = if (!enabled) {
                container.settingsRepository.update { it.copy(requireSessionPin = true) }
                true
            } else true
            if (!effective) return@launch
            val newPin = generateLivePin(current)
            container.liveScreenManager.updateState { it.copy(pin = newPin) }
        }
    }

    private fun generateLivePin(settings: com.ghoststream.core.model.AppSettings): String = when {
        settings.autoGeneratePin -> kotlin.random.Random.nextInt(1000, 9999).toString()
        else -> settings.manualPin.filter(Char::isDigit).padEnd(4, '0').take(6)
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

    fun navigateToQuickText() {
        viewModelScope.launch {
            if (container.liveScreenManager.state.value.isActive) {
                _events.emit(AppEvent.ShowMessage(application.getString(R.string.quick_text_blocked_by_live_screen)))
                return@launch
            }
            _events.emit(AppEvent.NavigateQuickText)
        }
    }

    fun navigateToLiveScreen() {
        viewModelScope.launch {
            if (container.sessionManager.sessionState.value.isSharing && !container.liveScreenManager.state.value.isActive) {
                _events.emit(AppEvent.ShowMessage(application.getString(R.string.live_screen_blocked_by_sharing)))
                return@launch
            }
            _events.emit(AppEvent.NavigateLiveScreen)
        }
    }

    fun sendQuickText(
        text: String,
        targetType: QuickTextTargetType,
        targetId: String?,
        targetName: String?,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            if (container.liveScreenManager.state.value.isActive) {
                _events.emit(AppEvent.ShowMessage(application.getString(R.string.quick_text_blocked_by_live_screen)))
                return@launch
            }
            container.historyRepository.addQuickTextMessage(
                QuickTextMessage(
                    id = java.util.UUID.randomUUID().toString(),
                    text = trimmed,
                    senderId = QUICK_TEXT_PHONE_ID,
                    senderName = application.getString(R.string.quick_text_this_phone),
                    targetType = targetType,
                    targetId = targetId,
                    targetName = targetName,
                    timestampMs = System.currentTimeMillis(),
                ),
            )
            _events.emit(AppEvent.ShowMessage(application.getString(R.string.quick_text_sent)))
        }
    }

    fun deleteQuickTextMessage(id: String) {
        viewModelScope.launch {
            container.historyRepository.deleteQuickTextMessage(id)
        }
    }

    fun clearQuickTextHistory() {
        viewModelScope.launch {
            container.historyRepository.clearQuickTextMessages()
        }
    }

    fun requestLiveScreenStart() {
        viewModelScope.launch {
            val current = container.liveScreenManager.state.value
            if (current.isActive) {
                container.debugLogRepository.log(
                    "MainViewModel",
                    "requestLiveScreenStart ignored because live screen is already active status=${current.status}",
                )
                _events.emit(AppEvent.NavigateLiveScreen)
                return@launch
            }
            val sessionState = container.sessionManager.sessionState.value
            if (sessionState.isSharing) {
                container.debugLogRepository.log("MainViewModel", "requestLiveScreenStart blocked because normal sharing is active")
                _events.emit(AppEvent.ShowMessage(application.getString(R.string.live_screen_blocked_by_sharing)))
                return@launch
            }
            val hasConflictingHeavyActivity = sessionState.connectedClients.any { client ->
                client.activity == com.ghoststream.core.model.ClientActivity.WATCHING_VIDEO ||
                    client.activity == com.ghoststream.core.model.ClientActivity.PLAYING_MUSIC ||
                    client.activity == com.ghoststream.core.model.ClientActivity.DOWNLOADING
            }
            if (hasConflictingHeavyActivity) {
                container.debugLogRepository.log(
                    "MainViewModel",
                    "requestLiveScreenStart blocked by conflicting client activity=${sessionState.connectedClients.joinToString { it.activity.name }}",
                )
                _events.emit(AppEvent.ShowMessage(application.getString(R.string.live_screen_busy_streams)))
                return@launch
            }
            container.debugLogRepository.log("MainViewModel", "requestLiveScreenStart requesting MediaProjection permission")
            _events.emit(AppEvent.RequestLiveScreenPermission)
        }
    }

    fun startLiveScreenCapture(resultCode: Int, permissionData: Intent) {
        viewModelScope.launch {
            if (container.sessionManager.sessionState.value.isSharing) {
                container.debugLogRepository.log("MainViewModel", "startLiveScreenCapture blocked because normal sharing is active")
                _events.emit(AppEvent.ShowMessage(application.getString(R.string.live_screen_blocked_by_sharing)))
                return@launch
            }
            val network = withContext(Dispatchers.IO) { container.networkInspector.inspect() }
            if (!network.isWifiOrHotspotReady) {
                _events.emit(AppEvent.ShowMessage(application.getString(R.string.sharing_connect_before_start)))
                return@launch
            }
            val settings = container.settingsRepository.settings.first()
            val binding = container.server.start(settings.preferredPort.coerceIn(1024, 65535))
            val displayUrl = com.ghoststream.core.model.buildSessionAccessUrl(
                sessionUrl = null,
                localAddress = network.localAddress,
                port = binding.port,
            ) ?: binding.url
            val liveUrl = displayUrl.trimEnd('/')
            val livePin = when {
                !settings.requireSessionPin -> null
                settings.autoGeneratePin -> kotlin.random.Random.nextInt(1000, 9999).toString()
                else -> settings.manualPin.filter(Char::isDigit).padEnd(4, '0').take(6)
            }
            container.liveScreenManager.updateState {
                it.copy(
                    status = LiveScreenStatus.STARTING,
                    sessionUrl = liveUrl,
                    displayUrl = liveUrl,
                    pin = livePin,
                    audioStatus = LiveAudioStatus.AUDIO_UNAVAILABLE,
                    startedAtEpochMs = System.currentTimeMillis(),
                    lastError = null,
                )
            }
            _events.emit(AppEvent.StartLiveScreenService(resultCode, permissionData))
            _events.emit(AppEvent.NavigateLiveScreen)
        }
    }

    fun onLiveScreenServiceStarted(
        width: Int,
        height: Int,
        fps: Int,
        bitrateKbps: Int,
        audioStatus: LiveAudioStatus,
    ) {
        container.liveScreenManager.updateState {
            it.copy(
                status = LiveScreenStatus.LIVE,
                width = width,
                height = height,
                fps = fps,
                bitrateKbps = bitrateKbps,
                audioStatus = audioStatus,
                lastError = null,
            )
        }
    }

    fun onLiveScreenServiceStopped(errorMessage: String? = null) {
        viewModelScope.launch {
            if (!container.sessionManager.sessionState.value.isSharing) {
                runCatching { container.server.stop() }
            }
            container.liveScreenManager.updateState {
                it.copy(
                    status = if (errorMessage == null) LiveScreenStatus.STOPPED else LiveScreenStatus.ERROR,
                    width = null,
                    height = null,
                    viewerCount = 0,
                    lastError = errorMessage,
                )
            }
            _events.emit(AppEvent.StopLiveScreenService)
            if (!errorMessage.isNullOrBlank()) {
                _events.emit(AppEvent.ShowMessage(errorMessage))
            }
        }
    }

    fun stopLiveScreenCapture() {
        viewModelScope.launch {
            container.liveScreenManager.updateState {
                it.copy(status = LiveScreenStatus.STOPPED, width = null, height = null, viewerCount = 0)
            }
            if (!container.sessionManager.sessionState.value.isSharing) {
                runCatching { container.server.stop() }
            }
            _events.emit(AppEvent.StopLiveScreenService)
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

    /**
     * Explicit user-initiated preparation for a single item.
     * MUST NOT be triggered automatically by browsing or discovery.
     */
    fun requestPrepareItem(itemId: String) {
        viewModelScope.launch {
            val item = container.storageRepository.findItemById(itemId)
            if (item == null) {
                _events.emit(AppEvent.ShowMessage(application.getString(R.string.message_file_no_longer_available)))
                return@launch
            }
            container.compatibilityPipeline.requestPreparation(item, priority = JobPriority.HIGH)
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
        if (container.liveScreenManager.state.value.isActive) {
            container.debugLogRepository.log("MainViewModel", "startSharingAfterReadyCheck blocked because live screen is active")
            pendingShareAfterNetworkReady.value = false
            startSharingInProgress.value = false
            _events.emit(AppEvent.ShowMessage(application.getString(R.string.sharing_blocked_by_live_screen)))
            return
        }
        when (val startResult = withContext(Dispatchers.IO) {
            container.sharingCoordinator.beginSharing(assumePreflightReady = true)
        }) {
            is ShareStartResult.Started -> {
                container.debugLogRepository.log("MainViewModel", "share started url=${startResult.url}")
                
                // DLNA handling will be triggered by session state change automatically via syncDlnaAnnouncer in init

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

    fun connectedQuickTextDevices(snapshot: MainUiState = uiState.value): List<com.ghoststream.core.model.QuickTextDevice> {
        val state = snapshot.sessionState
        val deviceNicknames = snapshot.settings.deviceNicknames
        val localAddress = state.networkAvailability.localAddress
        return buildList {
            add(
                com.ghoststream.core.model.QuickTextDevice(
                    id = QUICK_TEXT_PHONE_ID,
                    name = application.getString(R.string.quick_text_this_phone),
                    ipAddress = state.networkAvailability.localAddress ?: "127.0.0.1",
                    isHostPhone = true,
                ),
            )
            state.connectedClients
                .filter { client -> client.ipAddress.isNotBlank() }
                .filterNot { client -> client.ipAddress == localAddress || client.ipAddress == "127.0.0.1" || client.ipAddress == "::1" }
                .distinctBy { it.ipAddress }
                .sortedWith(
                    compareByDescending<com.ghoststream.core.model.ConnectedClient> { it.lastSeenEpochMs }
                        .thenBy { displayDeviceName(it.ipAddress, deviceNicknames).lowercase() },
                )
                .map { client ->
                    com.ghoststream.core.model.QuickTextDevice(
                        id = client.ipAddress,
                        name = displayDeviceName(client.ipAddress, deviceNicknames),
                        ipAddress = client.ipAddress,
                    )
                }
                .forEach(::add)
        }
    }

    /**
     * Explicit user-initiated preparation for all compatible items in the active session.
     * 
     * ARCHITECTURAL GUARDRAIL:
     * - This is a manual-only bulk action.
     * - Uses JobPriority.LOW so it can be preempted by active Play/Seek requests.
     * - MUST NOT be invoked by lifecycle, bootstrap, or browsing events.
     */
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
                    existing?.status == com.ghoststream.core.media.CompatibilityStatus.PREPARING ||
                    existing?.status == com.ghoststream.core.media.CompatibilityStatus.ANALYZING ||
                    existing?.status == com.ghoststream.core.media.CompatibilityStatus.FINALIZING
                if (!alreadyHandled) {
                    container.compatibilityPipeline.requestPreparation(item, com.ghoststream.core.media.JobPriority.LOW)
                    queuedCount++
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

    override fun onCleared() {
        super.onCleared()
        runCatching {
            val cm = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(networkCallback)
        }
        dlnaAnnouncer?.stop()
    }

    private fun syncDlnaAnnouncer(enabled: Boolean, session: SessionState) {
        if (enabled && session.isSharing && session.networkAvailability.localAddress != null) {
            val ip = session.networkAvailability.localAddress!!
            if (dlnaAnnouncer == null || dlnaAnnouncer?.ipAddress != ip) {
                dlnaAnnouncer?.stop()
                dlnaAnnouncer = DlnaAnnouncer(
                    deviceName = application.getString(R.string.dlna_server_name_template, Build.MODEL),
                    deviceUuid = java.util.UUID.nameUUIDFromBytes(application.packageName.toByteArray()).toString(),
                    serverPort = session.serverPort ?: 8080,
                    ipAddress = ip
                )
                dlnaAnnouncer?.start()
                container.debugLogRepository.log("MainViewModel", "DLNA Announcer started (ip=$ip)")
            }
        } else {
            if (dlnaAnnouncer != null) {
                dlnaAnnouncer?.stop()
                dlnaAnnouncer = null
                container.debugLogRepository.log("MainViewModel", "DLNA Announcer stopped")
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
