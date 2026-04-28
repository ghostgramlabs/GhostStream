package com.ghostgramlabs.directserve

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
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
import com.ghostgramlabs.directserve.service.LiveScreenCaptureService
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
import com.ghoststream.feature.session.LiveScreenControlScreen
import com.ghoststream.feature.settings.DlnaScreen
import com.ghoststream.feature.settings.HelpScreen
import com.ghoststream.feature.settings.PrivacyPolicyScreen
import com.ghoststream.feature.settings.SettingsScreen
import com.ghoststream.feature.history.HistoryScreen
import com.ghoststream.feature.history.QuickTextScreen
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
        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_QUICK_TEXT) {
            viewModel.navigateToQuickText()
        }
    }

    companion object {
        const val ACTION_OPEN_QUICK_TEXT = "com.ghostgramlabs.directserve.action.OPEN_QUICK_TEXT"
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
    var pendingLiveScreenStartAfterNotificationPermission by remember { mutableStateOf(false) }
    var pendingBatchSelectNavigation by remember { mutableStateOf(false) }
    var launchHandled by remember { mutableStateOf(false) }
    var lastSessionMessage by remember { mutableStateOf<String?>(uiState.sessionState.message) }
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
    val mediaServerPermissionLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) {
            viewModel.importAllMedia()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(SharedR.string.main_batch_media_access_needed))
            }
        }
    }
    val startMediaServerWithPermission = {
        if (hasBatchSelectionMediaAccess(context)) {
            viewModel.importAllMedia()
        } else {
            mediaServerPermissionLauncher.launch(requiredBatchSelectionPermissions())
        }
    }
    val liveScreenPermissionLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            viewModel.startLiveScreenCapture(result.resultCode, result.data!!)
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(SharedR.string.live_screen_permission_denied))
            }
        }
    }
    val requestLiveScreenProjection = {
        val projectionManager = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        liveScreenPermissionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
    val liveScreenAudioPermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (granted) {
            requestLiveScreenProjection()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(SharedR.string.live_screen_permission_denied))
            }
        }
    }
    val requestLiveScreenPermissions = {
        val needsAudioPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (needsAudioPermission) {
            liveScreenAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            requestLiveScreenProjection()
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        val shouldStartSharing = pendingStartService
        val shouldStartLiveScreen = pendingLiveScreenStartAfterNotificationPermission
        pendingStartService = false
        pendingLiveScreenStartAfterNotificationPermission = false

        if (shouldStartSharing) {
            startForegroundSharingService()
        }
        if (shouldStartLiveScreen) {
            requestLiveScreenPermissions()
        }
        if (!granted && (shouldStartSharing || shouldStartLiveScreen)) {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(SharedR.string.main_notification_permission_warning))
            }
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
                AppEvent.NavigateSession -> navController.navigate(Routes.Session) {
                    launchSingleTop = true
                }
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
                is AppEvent.NavigateHistory -> navController.navigate(Routes.History) {
                    launchSingleTop = true
                }
                AppEvent.NavigateQuickText -> navController.navigate(Routes.QuickText) {
                    launchSingleTop = true
                }
                AppEvent.NavigateLiveScreen -> navController.navigate(Routes.LiveScreen) {
                    launchSingleTop = true
                }
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
                AppEvent.RequestAllFilesAccess -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }.onFailure {
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(SharedR.string.main_error_open_settings))
                            }
                        }
                    }
                }
                AppEvent.RequestBatteryOptimizationExemption -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        runCatching {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }.recoverCatching {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }.onFailure {
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(SharedR.string.main_error_open_settings))
                            }
                        }
                    }
                }
                AppEvent.RequestLiveScreenPermission -> {
                    val needsNotificationPermission =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    if (needsNotificationPermission) {
                        pendingLiveScreenStartAfterNotificationPermission = true
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        requestLiveScreenPermissions()
                    }
                }
                is AppEvent.StartLiveScreenService -> {
                    runCatching {
                        LiveScreenCaptureService.start(context, event.resultCode, event.permissionData)
                    }.onFailure {
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(SharedR.string.live_screen_failed))
                        }
                    }
                }
                AppEvent.StopLiveScreenService -> runCatching { LiveScreenCaptureService.stop(context) }
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
                val finishOnboarding = {
                    viewModel.completeOnboarding()
                    navController.navigate(Routes.Home) {
                        popUpTo(Routes.Onboarding) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                OnboardingScreen(
                    onSkip = finishOnboarding,
                    onGetStarted = finishOnboarding,
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
                ResumeRefreshEffect(onResume = {
                    viewModel.refreshNetwork()
                    viewModel.refreshAllFilesAccessStatus()
                })
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
                    hasAllFilesAccess = uiState.hasAllFilesAccess,
                    libraryState = uiState.libraryState,
                    sessionState = uiState.sessionState,
                    settings = uiState.settings,
                    recentSessions = uiState.recentSessions,

                    connectionDiagnostics = uiState.connectionDiagnostics,
                    nearbyDiscoveryState = uiState.nearbyDiscoveryState,
                    connectingNearbyDeviceId = uiState.connectingNearbyDeviceId,
                    pendingUploadRequest = uiState.pendingUploadRequest,
                    isStartingShare = uiState.isStartingShare,
                    isLiveScreenActive = uiState.liveScreenState.isActive,
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
                    onOpenLibrary = { navController.navigate(Routes.Library) },
                    onOpenLiveScreen = viewModel::navigateToLiveScreen,
                    onOpenQuickText = viewModel::navigateToQuickText,
                    onOpenSettings = { navController.navigate(Routes.Settings) },
                    onOpenHistory = viewModel::navigateToHistory,
                    onOpenDlna = { navController.navigate(Routes.Dlna) },
                    onImportAllMedia = startMediaServerWithPermission,
                    onClearAllMedia = viewModel::clearAllMedia,
                    onRequestAllFilesAccess = viewModel::requestAllFilesAccess,
                    libraryImportingCount = uiState.libraryImportingCount,
                    onResolveUploadRequest = viewModel::resolveUploadRequest,
                    onToggleShareVideos = { viewModel.updateSettings { s -> s.copy(shareVideos = it) } },
                    onToggleShareMusic = { viewModel.updateSettings { s -> s.copy(shareMusic = it) } },
                    onToggleSharePhotos = { viewModel.updateSettings { s -> s.copy(sharePhotos = it) } },
                    onToggleShareFiles = { viewModel.updateSettings { s -> s.copy(shareFiles = it) } },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.Library) {
                SharedLibraryScreen(
                    libraryState = uiState.libraryState,
                    compatibilityJobs = uiState.compatibilityJobs,
                    libraryImportingCount = uiState.libraryImportingCount,
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
                        ?.takeIf {
                            it.status == com.ghoststream.core.media.CompatibilityStatus.PREPARING ||
                                it.status == com.ghoststream.core.media.CompatibilityStatus.ANALYZING ||
                                it.status == com.ghoststream.core.media.CompatibilityStatus.FINALIZING
                        }
                        ?.let { item to it }
                }
                val browserPrepQueuedCount = browserPrepCandidates.count { item ->
                    val status = uiState.compatibilityJobs[item.id]?.status
                    status == com.ghoststream.core.media.CompatibilityStatus.QUEUED ||
                        status == com.ghoststream.core.media.CompatibilityStatus.PREPARING ||
                        status == com.ghoststream.core.media.CompatibilityStatus.ANALYZING ||
                        status == com.ghoststream.core.media.CompatibilityStatus.FINALIZING
                }
                val browserPrepReadyCount = browserPrepCandidates.count { item ->
                    val job = uiState.compatibilityJobs[item.id]
                    job?.status == com.ghoststream.core.media.CompatibilityStatus.READY || job?.preparedAsset?.isComplete == true
                }
                val browserPrepPendingCount = (browserPrepCandidates.size - browserPrepReadyCount).coerceAtLeast(0)
                val liveLibraryItemIds = uiState.sessionState.selectedItems.mapTo(mutableSetOf()) { it.id }
                val liveLibraryFolderIds = uiState.sessionState.selectedFolders.mapTo(mutableSetOf()) { it.id }
                val pendingLibraryItemCount = uiState.libraryState.items.count { it.id !in liveLibraryItemIds }
                val pendingLibraryFolderCount = uiState.libraryState.folders.count { it.id !in liveLibraryFolderIds }
                ActiveSessionScreen(
                    sessionState = uiState.sessionState,
                    pendingLibraryItemCount = pendingLibraryItemCount,
                    pendingLibraryFolderCount = pendingLibraryFolderCount,
                    browserPrepTargetCount = browserPrepCandidates.size,
                    browserPrepQueuedCount = browserPrepQueuedCount,
                    browserPrepReadyCount = browserPrepReadyCount,
                    browserPrepPendingCount = browserPrepPendingCount,
                    browserPrepProgressPercent = activeBrowserPrep?.second?.progressPercent ?: 0,
                    browserPrepActiveFileName = activeBrowserPrep?.first?.displayName,
                    browserPrepManuallyTriggered = uiState.browserPrepManuallyTriggered,
                    hapticOnDeviceConnect = uiState.settings.hapticOnDeviceConnect,
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
                    onPrepareBrowserFiles = viewModel::prepareLiveBrowserFiles,
                    onStopBrowserFiles = viewModel::stopLiveBrowserFiles,
                    onRefreshLibrary = viewModel::refreshLiveLibrary,
                    onBack = { navController.popBackStack() },
                    onStopSharing = viewModel::requestStopSharing,
                    onBlockClient = viewModel::blockClient,
                    onUnblockClient = viewModel::unblockClient,
                    onRegeneratePin = viewModel::regeneratePin,
                    onDisconnectAll = viewModel::disconnectAll,
                    pendingUploadRequest = uiState.pendingUploadRequest,
                    onResolveUploadRequest = viewModel::resolveUploadRequest,
                    deviceNicknames = uiState.settings.deviceNicknames,
                    onUpdateDeviceNickname = viewModel::updateDeviceNickname,
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
                    onToggleDlna = { viewModel.updateSettings { current -> current.copy(dlnaEnabled = it) } },
                    onToggleHaptics = { viewModel.updateSettings { current -> current.copy(hapticOnDeviceConnect = it) } },
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
                    onToggleNotifyOnUploadRequest = { viewModel.updateSettings { current -> current.copy(notifyOnUploadRequest = it) } },
                    onToggleNotifyOnQuickText = { viewModel.updateSettings { current -> current.copy(notifyOnQuickText = it) } },
                    onToggleQuickTextEnabled = { viewModel.updateSettings { current -> current.copy(quickTextEnabled = it) } },
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
                    onOpenReceivedFilesLocation = {
                        runCatching {
                            context.startActivity(
                                Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        }.onFailure {
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(SharedR.string.main_no_file_app))
                            }
                        }
                    },
                    onOpenWifiSettings = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                    onOpenHotspotSettings = { context.startActivity(Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    onOpenHelp = { navController.navigate(Routes.Help) },
                    onOpenPrivacyPolicy = { navController.navigate(Routes.PrivacyPolicy) },
                    onViewOnboarding = { navController.navigate(Routes.OnboardingPreview) },
                    onRateApp = {
                        val pkg = context.packageName.removeSuffix(".debug")
                        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                            setPackage("com.android.vending")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(marketIntent)
                        } catch (_: ActivityNotFoundException) {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
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
            composable(Routes.QuickText) {
                QuickTextScreen(
                    messages = uiState.quickTextHistory,
                    devices = viewModel.connectedQuickTextDevices(uiState),
                    onBack = { navController.popBackStack() },
                    onSend = viewModel::sendQuickText,
                    onCopyMessage = {
                        clipboardManager.setText(AnnotatedString(it))
                        scope.launch { snackbarHostState.showSnackbar(context.getString(SharedR.string.main_link_copied)) }
                    },
                    onOpenLink = { link ->
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        }
                    },
                    onDeleteMessage = viewModel::deleteQuickTextMessage,
                    onClearAll = viewModel::clearQuickTextHistory,
                    onRefreshDevices = viewModel::refreshQuickTextDevices,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.LiveScreen) {
                LiveScreenControlScreen(
                    state = uiState.liveScreenState,
                    pinProtectionEnabled = uiState.settings.requireSessionPin,
                    onBack = { navController.popBackStack() },
                    onStart = viewModel::requestLiveScreenStart,
                    onStop = viewModel::stopLiveScreenCapture,
                    onCopyLink = {
                        uiState.liveScreenState.displayUrl?.let {
                            clipboardManager.setText(AnnotatedString(it))
                            scope.launch { snackbarHostState.showSnackbar(context.getString(SharedR.string.main_link_copied)) }
                        }
                    },
                    onTogglePinProtection = viewModel::updateLiveScreenPinProtection,
                    onRegeneratePin = viewModel::regenerateLiveScreenPin,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.Help) {
                HelpScreen(
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.Dlna) {
                DlnaScreen(
                    dlnaEnabled = uiState.settings.dlnaEnabled,
                    onToggleDlna = { viewModel.updateSettings { current -> current.copy(dlnaEnabled = it) } },
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
    const val QuickText = "quick_text"
    const val LiveScreen = "live_screen"
    const val Help = "help"
    const val PrivacyPolicy = "privacy_policy"
    const val Dlna = "dlna"
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
