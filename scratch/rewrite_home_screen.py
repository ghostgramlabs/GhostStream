import re
import os

FILE_PATH = r"c:\Users\sudhi\.gemini\antigravity\scratch\GhostStream\feature\home\src\main\java\com\ghoststream\feature\home\HomeScreen.kt"

with open(FILE_PATH, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Add new imports
if "import androidx.compose.material3.FilterChip" not in content:
    content = content.replace(
        "import androidx.compose.material3.CardDefaults\n",
        "import androidx.compose.material3.CardDefaults\nimport androidx.compose.material3.FilterChip\nimport androidx.compose.foundation.lazy.LazyRow\nimport androidx.compose.material3.ExperimentalMaterial3Api\n"
    )

# 2. Replace HomeScreen
home_screen_regex = re.compile(r'@OptIn\(ExperimentalLayoutApi::class\)\n@Composable\nfun HomeScreen\(.*?\n}\n\n@Composable\nprivate fun FeatureSectionsCard', re.DOTALL)

new_home_screen = """@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    hasAllFilesAccess: Boolean,
    libraryState: LibraryState,
    sessionState: SessionState,
    settings: com.ghoststream.core.model.AppSettings,
    recentSessions: List<RecentSession>,
    connectionDiagnostics: ConnectionDiagnostics,
    nearbyDiscoveryState: NearbyDiscoveryState,
    connectingNearbyDeviceId: String?,
    pendingUploadRequest: com.ghoststream.core.model.UploadRequest?,
    isStartingShare: Boolean,
    isLiveScreenActive: Boolean,
    onStartSharing: () -> Unit,
    onRefreshConnection: () -> Unit,
    onRefreshNearby: () -> Unit,
    onOpenNearbyDevice: (NearbyDevice) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenLiveScreen: () -> Unit,
    onOpenQuickText: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDlna: () -> Unit,
    libraryImportingCount: Int,
    onImportAllMedia: () -> Unit,
    onClearAllMedia: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onResolveUploadRequest: (String, Boolean) -> Unit,
    onToggleShareVideos: (Boolean) -> Unit,
    onToggleShareMusic: (Boolean) -> Unit,
    onToggleSharePhotos: (Boolean) -> Unit,
    onToggleShareFiles: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAllFilesDialog by rememberSaveable { mutableStateOf(false) }

    if (showAllFilesDialog) {
        AlertDialog(
            onDismissRequest = { showAllFilesDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 0.dp,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            iconContentColor = MaterialTheme.colorScheme.primary,
            icon = { Icon(Icons.Outlined.Collections, contentDescription = null) },
            title = { Text(stringResource(R.string.home_all_files_access_title)) },
            text = { Text(stringResource(R.string.home_all_files_access_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showAllFilesDialog = false
                        onRequestAllFilesAccess()
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ghostPrimaryButtonColors(),
                ) {
                    Text(stringResource(R.string.home_all_files_access_grant))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAllFilesDialog = false
                        onImportAllMedia()
                    }
                ) {
                    Text(stringResource(R.string.home_all_files_access_media_only))
                }
            }
        )
    }

    val appBackground by animateColorAsState(
        targetValue = if (sessionState.isSharing) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.background,
        animationSpec = tween(durationMillis = 800, easing = LinearOutSlowInEasing),
        label = "BackgroundTransition"
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(appBackground),
        contentPadding = PaddingValues(bottom = 64.dp),
    ) {
        item {
            TopBrandHeader(
                onOpenSettings = onOpenSettings,
                onOpenHistory = onOpenHistory
            )
        }

        item {
            SessionHeroSection(
                hasAllFilesAccess = hasAllFilesAccess,
                libraryState = libraryState,
                sessionState = sessionState,
                settings = settings,
                connectionDiagnostics = connectionDiagnostics,
                isStartingShare = isStartingShare,
                onOpenLibrary = onOpenLibrary,
                onStartSharing = onStartSharing,
                onImportAllMedia = onImportAllMedia,
                onClearAllMedia = onClearAllMedia,
                libraryImportingCount = libraryImportingCount,
                onRequestAllFilesAccess = { showAllFilesDialog = true },
            )
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }

        item {
            ContentFilterRow(
                settings = settings,
                libraryState = libraryState,
                onToggleVideos = onToggleShareVideos,
                onToggleMusic = onToggleShareMusic,
                onTogglePhotos = onToggleSharePhotos,
                onToggleFiles = onToggleShareFiles,
            )
        }

        item { Spacer(modifier = Modifier.height(48.dp)) }

        item {
            FeatureSections(
                settings = settings,
                onOpenLibrary = onOpenLibrary,
                onOpenSettings = onOpenSettings,
                onOpenHistory = onOpenHistory,
                onOpenLiveScreen = onOpenLiveScreen,
                onOpenQuickText = onOpenQuickText,
                onOpenDlna = onOpenDlna,
                isLiveScreenActive = isLiveScreenActive,
            )
        }

        item { Spacer(modifier = Modifier.height(40.dp)) }

        item {
            SupportPanel(
                sessionState = sessionState,
                connectionDiagnostics = connectionDiagnostics,
                nearbyDiscoveryState = nearbyDiscoveryState,
                connectingNearbyDeviceId = connectingNearbyDeviceId,
                onRefreshConnection = onRefreshConnection,
                onRefreshNearby = onRefreshNearby,
                onOpenNearbyDevice = onOpenNearbyDevice,
            )
        }

        if (recentSessions.isNotEmpty()) {
            item { Spacer(modifier = Modifier.height(32.dp)) }
            item {
                RecentSessionsSection(recentSessions = recentSessions)
            }
        }
    }

    pendingUploadRequest?.let { request ->
        val requesterIdentity = deviceIdentity(request.requesterIp)
        AlertDialog(
            onDismissRequest = { onResolveUploadRequest(request.id, false) },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 0.dp,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            iconContentColor = MaterialTheme.colorScheme.primary,
            icon = { Icon(Icons.Outlined.Collections, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.home_upload_request_title)) },
            text = {
                val fileText = if (request.fileCount > 1) stringResource(R.string.home_upload_request_files, request.fileCount) else stringResource(R.string.home_upload_request_single)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.home_upload_request_body, requesterIdentity.nameWithIp, fileText),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (request.fileCount == 1) {
                        Text(
                            request.fileName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        stringResource(R.string.home_upload_request_total_size, LocalizationUtils.formatBytes(androidx.compose.ui.platform.LocalContext.current, request.sizeBytes)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onResolveUploadRequest(request.id, true) },
                    shape = RoundedCornerShape(16.dp),
                    colors = ghostPrimaryButtonColors(),
                ) {
                    Text(stringResource(R.string.common_accept))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { onResolveUploadRequest(request.id, false) },
                    shape = RoundedCornerShape(16.dp),
                    colors = ghostSecondaryButtonColors(),
                ) {
                    Text(stringResource(R.string.common_decline))
                }
            },
        )
    }

}

@Composable
private fun FeatureSectionsCard"""
content = home_screen_regex.sub(new_home_screen, content)


# 3. Replace FeatureSectionsCard and FeatureRow
feature_sections_regex = re.compile(r'@Composable\nprivate fun FeatureSectionsCard\(.*?\n}\n\n@Composable\nprivate fun TopBrandHeader', re.DOTALL)

new_feature_sections = """@Composable
private fun FeatureSections(
    settings: com.ghoststream.core.model.AppSettings,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenLiveScreen: () -> Unit,
    onOpenQuickText: () -> Unit,
    onOpenDlna: () -> Unit,
    isLiveScreenActive: Boolean,
) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.home_section_library),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        FlatFeatureRow(
            icon = Icons.Outlined.Collections,
            title = stringResource(R.string.home_feature_library),
            detail = stringResource(R.string.home_feature_library_desc),
            onClick = onOpenLibrary,
        )

        Spacer(modifier = Modifier.height(40.dp))

        Text(
            text = stringResource(R.string.home_section_live_tools),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        FlatFeatureRow(
            icon = Icons.Outlined.LiveTv,
            title = stringResource(R.string.home_feature_live_screen),
            detail = stringResource(R.string.home_feature_live_screen_desc),
            onClick = onOpenLiveScreen,
            isAccent = true,
        )
        if (settings.quickTextEnabled) {
            FlatFeatureRow(
                icon = Icons.Outlined.Textsms,
                title = stringResource(R.string.home_feature_quick_text),
                detail = if (isLiveScreenActive) {
                    stringResource(R.string.home_feature_quick_text_disabled_live)
                } else {
                    stringResource(R.string.home_feature_quick_text_desc)
                },
                onClick = onOpenQuickText,
                enabled = !isLiveScreenActive,
                isAccent = true,
            )
        }

        Spacer(modifier = Modifier.height(40.dp))

        Text(
            text = stringResource(R.string.home_section_network_tv),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        FlatFeatureRow(
            icon = Icons.Outlined.NetworkCheck,
            title = stringResource(R.string.home_feature_dlna),
            detail = if (settings.dlnaEnabled) {
                stringResource(R.string.home_feature_dlna_enabled)
            } else {
                stringResource(R.string.home_feature_dlna_desc)
            },
            onClick = onOpenDlna,
        )
    }
}

@Composable
private fun FlatFeatureRow(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isAccent: Boolean = false,
) {
    val contentAlpha = if (enabled) 1f else 0.45f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = contentAlpha }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val containerColor = if (isAccent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
        val iconTint = if (isAccent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(containerColor, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TopBrandHeader"""
content = feature_sections_regex.sub(new_feature_sections, content)

# 4. Replace SessionHeroCard
hero_regex = re.compile(r'@OptIn\(ExperimentalLayoutApi::class\)\n@Composable\nprivate fun SessionHeroCard\(.*?\n}\n\n@Composable\nprivate fun ConnectedDevicesCard', re.DOTALL)

new_hero = """@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionHeroSection(
    hasAllFilesAccess: Boolean,
    libraryState: LibraryState,
    sessionState: SessionState,
    settings: com.ghoststream.core.model.AppSettings,
    connectionDiagnostics: ConnectionDiagnostics,
    isStartingShare: Boolean,
    onOpenLibrary: () -> Unit,
    onStartSharing: () -> Unit,
    onImportAllMedia: () -> Unit,
    onClearAllMedia: () -> Unit,
    libraryImportingCount: Int,
    onRequestAllFilesAccess: () -> Unit,
) {
    val canStartSession = sessionState.networkAvailability.isWifiOrHotspotReady
    val hasLibraryContent = libraryState.items.isNotEmpty() || libraryState.folders.isNotEmpty()
    val mediaServerModeActive = settings.mediaServerMode && hasLibraryContent
    var showNoNetworkDialog by rememberSaveable { mutableStateOf(false) }
    
    if (showNoNetworkDialog) {
        AlertDialog(
            onDismissRequest = { showNoNetworkDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 0.dp,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            iconContentColor = MaterialTheme.colorScheme.primary,
            icon = { Icon(Icons.Outlined.NetworkCheck, contentDescription = null) },
            title = { Text(stringResource(R.string.home_network_required_title)) },
            text = { 
                Text(stringResource(R.string.home_network_required_body))
            },
            confirmButton = {
                TextButton(onClick = { showNoNetworkDialog = false }) {
                    Text(stringResource(R.string.common_dismiss))
                }
            },
        )
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            LiveStatusIndicator(
                sessionState = sessionState,
                modifier = Modifier.align(Alignment.CenterStart)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (sessionState.isSharing || canStartSession) {
                    onStartSharing()
                } else {
                    showNoNetworkDialog = true
                }
            },
            enabled = !isStartingShare,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(64.dp),
            shape = RoundedCornerShape(32.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
            colors = ghostPrimaryButtonColors(),
        ) {
            if (isStartingShare) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    stringResource(R.string.home_button_starting),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    if (sessionState.isSharing) stringResource(R.string.home_button_open_session) else stringResource(R.string.home_button_start_session),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        
        if (!sessionState.isSharing && !canStartSession) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.home_no_network),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(0.85f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onOpenLibrary,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(
                    text = if (hasLibraryContent) stringResource(R.string.home_button_add_more) else stringResource(R.string.home_button_add_media),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = {
                    if (mediaServerModeActive) {
                        onClearAllMedia()
                    } else if (hasAllFilesAccess) {
                        onImportAllMedia()
                    } else {
                        onRequestAllFilesAccess()
                    }
                },
                enabled = libraryImportingCount == 0,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                if (libraryImportingCount > 0) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        if (mediaServerModeActive) stringResource(R.string.home_btn_stop_media_server) else stringResource(R.string.home_button_share_all_media),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "${libraryState.summary.totalItems} ITEMS • ${libraryState.folders.size} FOLDERS",
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun ConnectedDevicesCard"""
content = hero_regex.sub(new_hero, content)

# 5. Replace ContentFilterCard
filter_regex = re.compile(r'@OptIn\(ExperimentalLayoutApi::class\)\n@Composable\nprivate fun ContentFilterCard\(.*?\n}\n\n@Composable\nprivate fun PulsingLiveDot', re.DOTALL)

new_filter = """@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentFilterRow(
    settings: com.ghoststream.core.model.AppSettings,
    libraryState: LibraryState,
    onToggleVideos: (Boolean) -> Unit,
    onToggleMusic: (Boolean) -> Unit,
    onTogglePhotos: (Boolean) -> Unit,
    onToggleFiles: (Boolean) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FilterChip(
                selected = settings.shareVideos,
                onClick = { onToggleVideos(!settings.shareVideos) },
                label = { Text("${stringResource(R.string.home_content_filter_videos)} (${libraryState.summary.videos})") },
                leadingIcon = { Icon(Icons.Outlined.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
        }
        item {
            FilterChip(
                selected = settings.shareMusic,
                onClick = { onToggleMusic(!settings.shareMusic) },
                label = { Text("${stringResource(R.string.home_content_filter_music)} (${libraryState.summary.music})") },
                leadingIcon = { Icon(Icons.Outlined.LibraryMusic, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
        }
        item {
            FilterChip(
                selected = settings.sharePhotos,
                onClick = { onTogglePhotos(!settings.sharePhotos) },
                label = { Text("${stringResource(R.string.home_content_filter_photos)} (${libraryState.summary.photos})") },
                leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
        }
        item {
            FilterChip(
                selected = settings.shareFiles,
                onClick = { onToggleFiles(!settings.shareFiles) },
                label = { Text("${stringResource(R.string.home_content_filter_files)} (${libraryState.summary.files})") },
                leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
        }
    }
}

@Composable
private fun PulsingLiveDot"""
content = filter_regex.sub(new_filter, content)

# 6. Replace SupportPanel & RecentSessionsCard
support_regex = re.compile(r'@OptIn\(ExperimentalLayoutApi::class\)\n@Composable\nprivate fun SupportPanel\(.*?\n}\n\n@Composable\nprivate fun MinimalChip', re.DOTALL)

new_support = """@Composable
private fun SupportPanel(
    sessionState: SessionState,
    connectionDiagnostics: ConnectionDiagnostics,
    nearbyDiscoveryState: NearbyDiscoveryState,
    connectingNearbyDeviceId: String?,
    onRefreshConnection: () -> Unit,
    onRefreshNearby: () -> Unit,
    onOpenNearbyDevice: (NearbyDevice) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.home_nearby_open_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(
                onClick = onRefreshNearby,
            ) {
                Text(stringResource(R.string.common_refresh), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (nearbyDiscoveryState.devices.isEmpty()) {
            Text(
                stringResource(R.string.home_nearby_optional_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                softWrap = true,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                nearbyDiscoveryState.devices.take(3).forEach { device ->
                    NearbyDeviceRowFlat(
                        device = device,
                        isConnecting = connectingNearbyDeviceId == device.id,
                        onOpen = { onOpenNearbyDevice(device) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NearbyDeviceRowFlat(
    device: NearbyDevice,
    isConnecting: Boolean,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NearbyDeviceSummary(device = device, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(16.dp))
        Button(
            onClick = onOpen,
            enabled = !isConnecting,
            shape = RoundedCornerShape(16.dp),
            colors = ghostPrimaryButtonColors(),
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(Icons.Outlined.OpenInBrowser, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.home_nearby_action_open))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NearbyDeviceSummary(
    device: NearbyDevice,
    modifier: Modifier = Modifier,
) {
    val identity = deviceIdentity(device.address)
    val title = device.deviceLabel?.takeIf { it.isNotBlank() } ?: identity.nameWithIp
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = listOfNotNull(device.friendlyUrl, identity.ipAddress)
                .distinct()
                .joinToString(stringResource(R.string.common_separator_pipe)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MinimalChip(label = stringResource(R.string.home_nearby_device_available), showAccentDot = true)
            if (device.authRequired) {
                MinimalChip(label = stringResource(R.string.home_nearby_device_pin))
            }
        }
    }
}

@Composable
private fun RecentSessionsSection(
    recentSessions: List<RecentSession>,
) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.home_recent_shares),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        recentSessions.take(3).forEach { session ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    androidx.compose.ui.res.pluralStringResource(
                        R.plurals.home_saved_shares_items_count,
                        session.totalItems,
                        session.totalItems
                    ),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    DateUtils.getRelativeTimeSpanString(session.endedAtEpochMs).toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MinimalChip"""
content = support_regex.sub(new_support, content)


# 7. Add LiveStatusIndicator (if not present)
if "fun LiveStatusIndicator" not in content:
    content += """
@Composable
private fun LiveStatusIndicator(
    sessionState: SessionState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (sessionState.isSharing) {
            PulsingLiveDot(dotColor = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(10.dp))
            Text(stringResource(R.string.home_chip_sharing), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        } else if (sessionState.networkAvailability.isWifiOrHotspotReady) {
            Box(modifier = Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp)))
            Spacer(modifier = Modifier.width(10.dp))
            Text(stringResource(R.string.home_chip_ready), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        } else {
            Box(modifier = Modifier.size(8.dp).background(MaterialTheme.colorScheme.error, RoundedCornerShape(999.dp)))
            Spacer(modifier = Modifier.width(10.dp))
            Text(stringResource(R.string.home_chip_setup_needed), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}
"""

with open(FILE_PATH, 'w', encoding='utf-8') as f:
    f.write(content)
print("Updated HomeScreen.kt successfully.")
