package com.ghoststream.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ghoststream.app.service.GhostStreamForegroundService
import com.ghoststream.app.state.AppEvent
import com.ghoststream.app.state.MainViewModel
import com.ghoststream.app.ui.theme.GhostStreamTheme
import com.ghoststream.feature.home.HomeScreen
import com.ghoststream.feature.library.AddFilesScreen
import com.ghoststream.feature.library.AddFolderScreen
import com.ghoststream.feature.library.BatchSelectScreen
import com.ghoststream.feature.library.SharedLibraryScreen
import com.ghoststream.feature.networksetup.NetworkSetupScreen
import com.ghoststream.feature.onboarding.OnboardingScreen
import com.ghoststream.feature.session.ActiveSessionScreen
import com.ghoststream.feature.settings.HelpScreen
import com.ghoststream.feature.settings.SettingsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory((application as GhostStreamApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            GhostStreamTheme {
                GhostStreamApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun GhostStreamApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var pendingStartService by remember { mutableStateOf(false) }
    var launchHandled by remember { mutableStateOf(false) }
    var lastSessionMessage by remember { mutableStateOf<String?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (!granted && pendingStartService) {
            scope.launch {
                snackbarHostState.showSnackbar("Sharing can still run, but notification visibility is limited.")
            }
        }
        pendingStartService = false
    }

    LaunchedEffect(uiState.isReady, uiState.settings.onboardingCompleted) {
        if (uiState.isReady && !launchHandled) {
            launchHandled = true
            val route = if (uiState.settings.onboardingCompleted) Routes.Home else Routes.Onboarding
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
        if (uiState.sessionState.isSharing && SessionAccessUrl(uiState.sessionState) != null) {
            navController.navigate(Routes.Session) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(uiState.sessionState.isSharing, uiState.sessionState.message) {
        val message = uiState.sessionState.message
        if (uiState.sessionState.isSharing) {
            lastSessionMessage = null
        } else if (message.isNotBlank() && message != "Not sharing" && message != lastSessionMessage) {
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
                AppEvent.StartSharingService -> {
                    runCatching {
                        GhostStreamForegroundService.start(context)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingStartService = true
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }.onFailure {
                        // Ignore crashes here, falling back to basic background sharing
                    }
                }
                AppEvent.StopSharingService -> runCatching { GhostStreamForegroundService.stop(context) }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Splash,
            modifier = Modifier.padding(innerPadding).background(MaterialTheme.colorScheme.background),
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
            composable(Routes.Home) {
                ResumeRefreshEffect(onResume = viewModel::refreshNetwork)
                HomeScreen(
                    libraryState = uiState.libraryState,
                    sessionState = uiState.sessionState,
                    recentSessions = uiState.recentSessions,
                    isStartingShare = uiState.isStartingShare,
                    onStartSharing = viewModel::requestStartSharing,
                    onAddFiles = { navController.navigate(Routes.AddFiles) },
                    onAddFolder = { navController.navigate(Routes.AddFolder) },
                    onBatchSelect = {
                        viewModel.loadSmartGroups()
                        navController.navigate(Routes.BatchSelect)
                    },
                    onOpenLibrary = { navController.navigate(Routes.Library) },
                    onOpenSettings = { navController.navigate(Routes.Settings) },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.Library) {
                SharedLibraryScreen(
                    libraryState = uiState.libraryState,
                    compatibilityJobs = uiState.compatibilityJobs,
                    onRemoveItem = viewModel::removeItem,
                    onRemoveFolder = viewModel::removeFolder,
                    onOpenAddFiles = { navController.navigate(Routes.AddFiles) },
                    onOpenAddFolder = { navController.navigate(Routes.AddFolder) },
                    onOpenBatchSelect = {
                        viewModel.loadSmartGroups()
                        navController.navigate(Routes.BatchSelect)
                    },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.AddFiles) {
                AddFilesScreen(
                    onBack = { navController.popBackStack() },
                    onAddSelected = viewModel::addFiles,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.AddFolder) {
                AddFolderScreen(
                    onBack = { navController.popBackStack() },
                    onAddFolder = viewModel::addFolder,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.BatchSelect) {
                BatchSelectScreen(
                    groups = uiState.smartGroups,
                    onBack = { navController.popBackStack() },
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
                    onOpenWifiSettings = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                    onOpenHotspotSettings = {
                        context.startActivity(Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    onRetry = viewModel::refreshNetwork,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.Session) {
                ActiveSessionScreen(
                    sessionState = uiState.sessionState,
                    hapticOnDeviceConnect = uiState.settings.hapticOnDeviceConnect,
                    onCopyLink = {
                        SessionAccessUrl(uiState.sessionState)?.let { url ->
                            clipboardManager.setText(AnnotatedString(url))
                            scope.launch { snackbarHostState.showSnackbar("Link copied") }
                        }
                    },
                    onShareLink = {
                        SessionAccessUrl(uiState.sessionState)?.let { url ->
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share GhostStream link"))
                        }
                    },
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
                    onToggleKeepScreenAwake = { viewModel.updateSettings { current -> current.copy(keepScreenAwake = it) } },
                    onToggleHaptics = { viewModel.updateSettings { current -> current.copy(hapticOnDeviceConnect = it) } },
                    onToggleTransferSpeed = { viewModel.updateSettings { current -> current.copy(showTransferSpeed = it) } },
                    onToggleRecentSessions = { viewModel.updateSettings { current -> current.copy(showRecentSessions = it) } },
                    onToggleRequirePin = { viewModel.updateSettings { current -> current.copy(requireSessionPin = it) } },
                    onToggleAutoGeneratePin = { viewModel.updateSettings { current -> current.copy(autoGeneratePin = it) } },
                    onToggleClearAuthOnStop = { viewModel.updateSettings { current -> current.copy(clearAuthOnStop = it) } },
                    onToggleGhostMode = { viewModel.updateSettings { current -> current.copy(ghostMode = it) } },
                    onToggleDarkBrowserTheme = { viewModel.updateSettings { current -> current.copy(forceDarkBrowserTheme = it) } },
                    onToggleShowThumbnails = { viewModel.updateSettings { current -> current.copy(showThumbnails = it) } },
                    onToggleLargeTvCards = { viewModel.updateSettings { current -> current.copy(largeTvCards = it) } },
                    onToggleProminentDownloads = { viewModel.updateSettings { current -> current.copy(prominentDownloadButton = it) } },
                    onAutoStopSelected = viewModel::updateAutoStop,
                    onManualPinChanged = { pin -> viewModel.updateSettings { current -> current.copy(manualPin = pin) } },
                    onOpenWifiSettings = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                    onOpenHotspotSettings = { context.startActivity(Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    onOpenHelp = { navController.navigate(Routes.Help) },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable(Routes.Help) {
                HelpScreen(modifier = Modifier.padding(innerPadding))
            }
        }
    }
}

@Composable
private fun SplashRoute() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF03060A), Color(0xFF0A121E), Color(0xFF030508)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(
                imageVector = Icons.Outlined.PlayArrow,
                contentDescription = null,
                tint = Color(0xFF87E6FF),
                modifier = Modifier.size(88.dp),
            )
            Spacer(modifier = Modifier.height(18.dp))
            Text("GhostStream", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            Text("File Transfer & Stream", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun SessionAccessUrl(state: com.ghoststream.core.model.SessionState): String? {
    return state.sessionUrl
        ?.takeIf { it.startsWith("http://") && !it.startsWith("http://:") }
        ?: state.networkAvailability.localAddress?.let { address ->
        state.serverPort?.let { port -> "http://$address:$port" }
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
    const val Onboarding = "onboarding"
    const val Home = "home"
    const val Library = "library"
    const val AddFiles = "add_files"
    const val AddFolder = "add_folder"
    const val BatchSelect = "batch_select"
    const val NetworkSetup = "network_setup"
    const val Session = "session"
    const val Settings = "settings"
    const val Help = "help"
}
