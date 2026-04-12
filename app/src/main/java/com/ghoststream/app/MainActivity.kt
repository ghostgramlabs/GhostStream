package com.ghostgramlabs.directserve

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ghostgramlabs.directserve.service.GhostStreamForegroundService
import com.ghostgramlabs.directserve.localization.LanguageSelectionScreen
import com.ghostgramlabs.directserve.state.AppEvent
import com.ghostgramlabs.directserve.state.MainViewModel
import com.ghostgramlabs.directserve.ui.theme.GhostStreamTheme
import com.ghoststream.core.model.resolvedAccessUrl
import com.ghoststream.core.model.ThemeMode
import com.ghoststream.feature.home.HomeScreen
import com.ghoststream.feature.library.AddFilesRoute
import com.ghoststream.feature.library.AddFolderRoute
import com.ghoststream.feature.library.BatchSelectRoute
import com.ghoststream.feature.library.SharedLibraryScreen
import com.ghoststream.feature.networksetup.NetworkSetupScreen
import com.ghoststream.feature.onboarding.OnboardingScreen
import com.ghoststream.feature.session.ActiveSessionScreen
import com.ghoststream.feature.settings.HelpScreen
import com.ghoststream.feature.settings.PrivacyPolicyScreen
import com.ghoststream.feature.settings.SettingsScreen
import com.ghoststream.feature.history.HistoryScreen
import com.ghostgramlabs.directserve.core.resources.R as SharedR
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory((application as GhostStreamApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            GhostStreamRoot(viewModel = viewModel)
        }
    }
}

@Composable
private fun GhostStreamRoot(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    GhostStreamTheme(themeMode = uiState.settings.themeMode) {
        GhostStreamApp(viewModel = viewModel, uiState = uiState)
    }
}

@Composable
private fun GhostStreamApp(viewModel: MainViewModel, uiState: com.ghostgramlabs.directserve.state.MainUiState) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var pendingStartService by remember { mutableStateOf(false) }
    var pendingBatchSelectNavigation by remember { mutableStateOf(false) }
    var launchHandled by remember { mutableStateOf(false) }
    var lastSessionMessage by remember { mutableStateOf<String?>(null) }
    var allowHomeBackToResumeSession by remember { mutableStateOf(false) }
    val startForegroundSharingService = remember(context, viewModel) {
        {
            runCatching {
                GhostStreamForegroundService.start(context)
            }.onFailure {
                viewModel.onServiceStartFailure(
                    context.getString(SharedR.string.main_service_background_protection_failed),
                )
            }
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (pendingStartService) {
            startForegroundSharingService()
            if (!granted) {
                scope.launch {
                    snackbarHostState.showSnackbar(context.getString(SharedR.string.main_notification_permission_warning))
                }
            }
        }
        pendingStartService = false
    }
    val batchMediaPermissionLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { _ ->
        val hasAccess = hasBatchSelectionMediaAccess(context)
        if (hasAccess) {
            viewModel.loadSmartGroups()
            if (pendingBatchSelectNavigation) {
                navController.navigate(Routes.BatchSelect) {
                    launchSingleTop = true
                }
            }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(SharedR.string.main_batch_media_access_needed))
            }
        }
        pendingBatchSelectNavigation = false
    }
    val openBatchSelect = {
        if (hasBatchSelectionMediaAccess(context)) {
            viewModel.loadSmartGroups()
            navController.navigate(Routes.BatchSelect) {
                launchSingleTop = true
            }
        } else {
            pendingBatchSelectNavigation = true
            batchMediaPermissionLauncher.launch(requiredBatchSelectionPermissions())
        }
    }
    val requestBatchSelectAccess = {
        if (hasBatchSelectionMediaAccess(context)) {
            viewModel.loadSmartGroups()
        } else {
            pendingBatchSelectNavigation = false
            batchMediaPermissionLauncher.launch(requiredBatchSelectionPermissions())
        }
    }

    LaunchedEffect(
        uiState.isReady,
        uiState.settings.languageSelectionCompleted,
        uiState.settings.onboardingCompleted,
    ) {
        if (uiState.isReady && !launchHandled) {
            launchHandled = true
            val route = when {
                !uiState.settings.languageSelectionCompleted -> Routes.Language
                uiState.settings.onboardingCompleted -> Routes.Home
                else -> Routes.Onboarding
            }
            navController.navigate(route) {
                popUpTo(Routes.Splash) { inclusive = true }
            }
        }
    }

    LaunchedEffect(
        uiState.sessionState.isSharing,
        uiState.sessionState.sessionUrl,
        uiState.sessionState.networkAvailability.localAddress,
        uiState.sessionState.serverPort,
    ) {
        if (uiState.sessionState.isSharing && uiState.sessionState.resolvedAccessUrl() != null) {
            allowHomeBackToResumeSession = false
            navController.navigate(Routes.Session) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(uiState.sessionState.isSharing) {
        if (!uiState.sessionState.isSharing) {
            allowHomeBackToResumeSession = false
        }
    }

    LaunchedEffect(uiState.sessionState.isSharing, uiState.sessionState.message) {
        val message = uiState.sessionState.message
        if (uiState.sessionState.isSharing) {
            lastSessionMessage = null
        } else if (message.isNotBlank() && message != context.getString(SharedR.string.main_not_sharing) && message != lastSessionMessage) {
            lastSessionMessage = message
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AppEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
                AppEvent.NavigateNetworkSetup -> navController.navigate(Routes.NetworkSetup)
                AppEvent.NavigateSession -> navController.navigate(Routes.Session)
                AppEvent.NavigateHome -> navController.navigate(Routes.Home) {
                    popUpTo(Routes.Home) { inclusive = true }
                }
                AppEvent.NavigateLibrary -> navController.navigate(Routes.Library) {
                    launchSingleTop = true
                }
                AppEvent.StartSharingService -> {
                    val needsNotificationPermission =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    if (needsNotificationPermission) {
                        pendingStartService = true
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        startForegroundSharingService()
                    }
                }
                AppEvent.StopSharingService -> runCatching { GhostStreamForegroundService.stop(context) }
                is AppEvent.ShareDebugLog -> {
                    val emailSelector = Intent(Intent.ACTION_SENDTO).apply {
                        data = android.net.Uri.parse("mailto:")
                    }
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, context.getString(SharedR.string.main_debug_log_subject))
                        putExtra(
                            Intent.EXTRA_TEXT,
                            context.getString(SharedR.string.main_debug_log_body),
                        )
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        clipData = ClipData.newRawUri(context.getString(SharedR.string.main_debug_log_subject), event.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        selector = emailSelector
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(shareIntent, context.getString(SharedR.string.main_debug_log_chooser)))
                    }.onFailure {
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(SharedR.string.main_no_email_app))
                        }
                    }
                }
                is AppEvent.OpenExternalUrl -> {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(event.url)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    }.onFailure {
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(SharedR.string.main_no_browser_app))
                        }
                    }
                }
                is AppEvent.NavigateHistory -> navController.navigate(Routes.History)
                is AppEvent.OpenFile -> {
                    runCatching {
                        val uri = android.net.Uri.parse(event.fileUri)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }.onFailure {
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(SharedR.string.main_no_file_app))
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Splash,
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
        ) {
            composable(Routes.Splash) {
                SplashRoute()
            }
            composable(Routes.Onboarding) {
                OnboardingScreen(
                    onSkip = viewModel::completeOnboarding,
                    onGetStarted = viewModel::completeOnboarding,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.Language) {
                var selectedLanguageTag by remember { mutableStateOf(viewModel.detectInitialLanguageTag()) }
                LanguageSelectionScreen(
                    selectedLanguageTag = selectedLanguageTag,
                    onLanguageSelected = {
                        selectedLanguageTag = it
                        viewModel.selectLanguage(it, completeSelection = false)
                    },
                    onContinue = {
                        viewModel.selectLanguage(selectedLanguageTag, completeSelection = true)
                        navController.navigate(Routes.Onboarding) {
                            popUpTo(Routes.Language) { inclusive = true }
                        }
                    },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.Home) {
                BackHandler(
                    enabled = allowHomeBackToResumeSession && uiState.sessionState.isSharing,
                ) {
                    allowHomeBackToResumeSession = false
                    navController.navigate(Routes.Session) {
                        launchSingleTop = true
                    }
                }
                ResumeRefreshEffect(onResume = viewModel::refreshNetwork)
                LaunchedEffect(uiState.sessionState.isSharing) {
                    if (uiState.sessionState.isSharing) {
                        viewModel.stopNearbyDiscovery()
                    } else {
                        viewModel.startNearbyDiscovery()
                    }
                }
                DisposableEffect(Unit) {
                    onDispose { viewModel.stopNearbyDiscovery() }
                }
                HomeScreen(
                    libraryState = uiState.libraryState,
                    sessionState = uiState.sessionState,
                    recentSessions = uiState.recentSessions,

                    connectionDiagnostics = uiState.connectionDiagnostics,
                    nearbyDiscoveryState = uiState.nearbyDiscoveryState,
                    connectingNearbyDeviceId = uiState.connectingNearbyDeviceId,
                    pendingUploadRequest = uiState.pendingUploadRequest,
                    isStartingShare = uiState.isStartingShare,
                    onStartSharing = {
                        if (uiState.sessionState.isSharing) {
                            allowHomeBackToResumeSession = false
                            navController.navigate(Routes.Session) {
                                launchSingleTop = true
                            }
                        } else {
                            viewModel.requestStartSharing()
                        }
                    },

                    onRefreshConnection = viewModel::refreshNetwork,
                    onRefreshNearby = viewModel::refreshNearbyDiscovery,
                    onOpenNearbyDevice = viewModel::openNearbyDevice,
                    onAddFiles = { navController.navigate(Routes.AddFiles) },
                    onAddFolder = { navController.navigate(Routes.AddFolder) },
                    onOpenLibrary = { navController.navigate(Routes.Library) },
                    onOpenSettings = { navController.navigate(Routes.Settings) },
                    onOpenHistory = viewModel::navigateToHistory,
                    onResolveUploadRequest = viewModel::resolveUploadRequest,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.Library) {
                SharedLibraryScreen(
                    libraryState = uiState.libraryState,
                    compatibilityJobs = uiState.compatibilityJobs,
                    showThumbnails = uiState.settings.showThumbnails,
                    onBack = { navController.popBackStack() },
                    onPrepareItem = viewModel::requestPrepareItem,
                    onClearAll = viewModel::clearLibrarySelection,
                    onRemoveItem = viewModel::removeItem,
                    onRemoveFolder = viewModel::removeFolder,
                    onOpenAddFiles = { navController.navigate(Routes.AddFiles) },
                    onOpenAddFolder = { navController.navigate(Routes.AddFolder) },
                    onOpenBatchSelect = openBatchSelect,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.AddFiles) {
                AddFilesRoute(
                    onBack = { navController.popBackStack() },
                    onAddSelected = viewModel::addFiles,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.AddFolder) {
                AddFolderRoute(
                    onBack = { navController.popBackStack() },
                    onAddFolder = viewModel::addFolder,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.BatchSelect) {
                val hasMediaAccess = hasBatchSelectionMediaAccess(context)
                LaunchedEffect(hasMediaAccess) {
                    if (hasMediaAccess) {
                        viewModel.loadSmartGroups()
                    }
                }
                BatchSelectRoute(
                    groups = uiState.smartGroups,
                    isLoading = uiState.smartGroupsLoading,
                    hasMediaAccess = hasMediaAccess,
                    onBack = { navController.popBackStack() },
                    onRequestAccess = requestBatchSelectAccess,
                    onRefresh = viewModel::loadSmartGroups,
                    onAddGroup = viewModel::addSmartSelection,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.NetworkSetup) {
                ResumeRefreshEffect(onResume = viewModel::refreshNetwork)
                LaunchedEffect(
                    uiState.pendingShareAfterNetworkReady,
                    uiState.sessionState.networkAvailability.isReady,
                ) {
                    if (uiState.pendingShareAfterNetworkReady && uiState.sessionState.networkAvailability.isReady) {
                        viewModel.resumePendingShareAfterNetworkReady()
                    }
                }
                NetworkSetupScreen(
                    networkAvailability = uiState.sessionState.networkAvailability,
                    onBack = { navController.popBackStack() },
                    onOpenWifiSettings = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                    onOpenHotspotSettings = {
                        context.startActivity(Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    onRetry = viewModel::refreshNetwork,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.Session) {
                BackHandler(enabled = uiState.sessionState.isSharing) {
                    allowHomeBackToResumeSession = true
                    navController.popBackStack()
                }
                KeepScreenAwakeEffect(
                    enabled = uiState.settings.keepScreenAwake && uiState.sessionState.isSharing,
                )
                val browserPrepCandidates = uiState.sessionState.selectedItems.filter { item ->
                    item.playbackDecision.mode != com.ghoststream.core.model.PlaybackMode.DIRECT
                }
                val activeBrowserPrep = browserPrepCandidates.firstNotNullOfOrNull { item ->
                    uiState.compatibilityJobs[item.id]
                        ?.takeIf { it.status == com.ghoststream.core.media.CompatibilityStatus.PREPARING }
                        ?.let { item to it }
                }
                val browserPrepReadyCount = browserPrepCandidates.count { item ->
                    val job = uiState.compatibilityJobs[item.id]
                    job?.status == com.ghoststream.core.media.CompatibilityStatus.READY || job?.preparedAsset?.isComplete == true
                }
                val browserPrepPendingCount = (browserPrepCandidates.size - browserPrepReadyCount).coerceAtLeast(0)
                ActiveSessionScreen(
                    sessionState = uiState.sessionState,
                    browserPrepTargetCount = browserPrepCandidates.size,
                    browserPrepReadyCount = browserPrepReadyCount,
                    browserPrepPendingCount = browserPrepPendingCount,
                    browserPrepProgressPercent = activeBrowserPrep?.second?.progressPercent ?: 0,
                    browserPrepActiveFileName = activeBrowserPrep?.first?.displayName,
                    hapticOnDeviceConnect = uiState.settings.hapticOnDeviceConnect,
                    showTransferSpeed = uiState.settings.showTransferSpeed,
                    onCopyLink = {
                        val url = uiState.sessionState.resolvedAccessUrl()
                        if (url != null) {
                            clipboardManager.setText(AnnotatedString(url))
                            scope.launch { snackbarHostState.showSnackbar(context.getString(SharedR.string.main_link_copied)) }
                        } else {
                            viewModel.refreshNetwork()
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(SharedR.string.main_link_preparing))
                            }
                        }
                    },
                    onShareLink = {
                        val url = uiState.sessionState.resolvedAccessUrl()
                        if (url != null) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }
                            context.startActivity(Intent.createChooser(intent, context.getString(SharedR.string.main_share_link_chooser)))
                        } else {
                            viewModel.refreshNetwork()
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(SharedR.string.main_link_not_ready))
                            }
                        }
                    },
                    onTogglePinProtection = viewModel::updateSessionPinProtection,
                    onBack = { navController.popBackStack() },
                    onStopSharing = viewModel::requestStopSharing,
                    onBlockClient = viewModel::blockClient,
                    onUnblockClient = viewModel::unblockClient,
                    onRegeneratePin = viewModel::regeneratePin,
                    onDisconnectAll = viewModel::disconnectAll,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.Settings) {
                SettingsScreen(
                    settings = uiState.settings,
                    recentSessions = uiState.recentSessions,
                    currentLanguageLabel = selectedLanguageLabel(uiState.settings.languageTag),
                    appVersionLabel = viewModel.versionLabel(),
                    onToggleKeepScreenAwake = { viewModel.updateSettings { current -> current.copy(keepScreenAwake = it) } },
                    onToggleHaptics = { viewModel.updateSettings { current -> current.copy(hapticOnDeviceConnect = it) } },
                    onToggleTransferSpeed = { viewModel.updateSettings { current -> current.copy(showTransferSpeed = it) } },
                    onToggleRecentSessions = { viewModel.updateSettings { current -> current.copy(showRecentSessions = it) } },
                    onToggleRequirePin = { viewModel.updateSettings { current -> current.copy(requireSessionPin = it) } },
                    onToggleAutoGeneratePin = { viewModel.updateSettings { current -> current.copy(autoGeneratePin = it) } },
                    onToggleClearAuthOnStop = { viewModel.updateSettings { current -> current.copy(clearAuthOnStop = it) } },
                    onToggleGhostMode = { viewModel.updateSettings { current -> current.copy(ghostMode = it) } },
                    onThemeModeSelected = { themeMode ->
                        viewModel.updateSettings { current -> current.copy(themeMode = themeMode) }
                    },
                    onToggleShowThumbnails = { viewModel.updateSettings { current -> current.copy(showThumbnails = it) } },
                    onToggleLargeTvCards = { viewModel.updateSettings { current -> current.copy(largeTvCards = it) } },
                    onToggleProminentDownloads = { viewModel.updateSettings { current -> current.copy(prominentDownloadButton = it) } },
                    onTogglePreventDownload = { viewModel.updateSettings { current -> current.copy(preventDownload = it) } },
                    onToggleRequireUploadApproval = { viewModel.updateSettings { current -> current.copy(requireUploadApproval = it) } },
                    onToggleNotifyOnDeviceConnect = { viewModel.updateSettings { current -> current.copy(notifyOnDeviceConnect = it) } },
                    onToggleNotifyOnFileDownload = { viewModel.updateSettings { current -> current.copy(notifyOnFileDownload = it) } },
                    onToggleNotifyOnUploadRequest = { viewModel.updateSettings { current -> current.copy(notifyOnUploadRequest = it) } },
                    onToggleRequireDeviceApproval = { viewModel.updateSettings { current -> current.copy(requireDeviceApproval = it) } },
                    onAutoStopSelected = viewModel::updateAutoStop,
                    onPreferredPortChanged = { port ->
                        viewModel.updateSettings { current ->
                            current.copy(
                                preferredPort = port.toIntOrNull()?.coerceIn(1024, 65535) ?: current.preferredPort,
                            )
                        }
                    },
                    onManualPinChanged = { pin -> viewModel.updateSettings { current -> current.copy(manualPin = pin) } },
                    onBack = { navController.popBackStack() },
                    onOpenLanguage = { navController.navigate(Routes.LanguageSettings) },
                    onOpenWifiSettings = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                    onOpenHotspotSettings = { context.startActivity(Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    onOpenHelp = { navController.navigate(Routes.Help) },
                    onOpenPrivacyPolicy = { navController.navigate(Routes.PrivacyPolicy) },
                    onViewOnboarding = { navController.navigate(Routes.OnboardingPreview) },
                    showDebugTools = BuildConfig.DEBUG,
                    debugLogLocation = viewModel.debugLogLocationDescription(),
                    onShareDebugLog = viewModel::shareDebugLog,
                    onClearDebugLog = viewModel::clearDebugLog,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.LanguageSettings) {
                var selectedLanguageTag by remember { mutableStateOf(uiState.settings.languageTag ?: viewModel.detectInitialLanguageTag()) }
                LanguageSelectionScreen(
                    selectedLanguageTag = selectedLanguageTag,
                    onLanguageSelected = {
                        selectedLanguageTag = it
                        viewModel.selectLanguage(it, completeSelection = true)
                    },
                    onContinue = { navController.popBackStack() },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.OnboardingPreview) {
                OnboardingScreen(
                    onSkip = { navController.popBackStack() },
                    onGetStarted = { navController.popBackStack() },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.PrivacyPolicy) {
                PrivacyPolicyScreen(
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.History) {
                HistoryScreen(
                    history = uiState.transferHistory,
                    onBack = { navController.popBackStack() },
                    onDeleteRecord = viewModel::deleteHistoryRecord,
                    onOpenFile = viewModel::openReceivedFile,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.Help) {
                HelpScreen(
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

@Composable
private fun SplashRoute() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(
                imageVector = Icons.Outlined.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(88.dp),
            )
            Spacer(modifier = Modifier.height(18.dp))
            Text(stringResource(SharedR.string.splash_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(stringResource(SharedR.string.splash_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResumeRefreshEffect(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private object Routes {
    const val Splash = "splash"
    const val Language = "language"
    const val LanguageSettings = "language_settings"
    const val Onboarding = "onboarding"
    const val OnboardingPreview = "onboarding_preview"
    const val Home = "home"
    const val Library = "library"
    const val AddFiles = "add_files"
    const val AddFolder = "add_folder"
    const val BatchSelect = "batch_select"
    const val NetworkSetup = "network_setup"
    const val Session = "session"
    const val Settings = "settings"
    const val History = "history"
    const val Help = "help"
    const val PrivacyPolicy = "privacy_policy"
}

private fun selectedLanguageLabel(languageTag: String?): String {
    return com.ghostgramlabs.directserve.localization.AppLanguages.resolve(languageTag).nativeName
}

@Composable
private fun KeepScreenAwakeEffect(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val previous = view.keepScreenOn
        view.keepScreenOn = enabled
        onDispose {
            view.keepScreenOn = previous
        }
    }
}

private fun requiredBatchSelectionPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        buildList {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        }.toTypedArray()
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun hasBatchSelectionMediaAccess(context: android.content.Context): Boolean {
    val granted = requiredBatchSelectionPermissions().any { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    if (granted) {
        return true
    }
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ) == PackageManager.PERMISSION_GRANTED
}
