package com.ghoststream.feature.home

import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Textsms
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostgramlabs.directserve.core.resources.R
import com.ghostgramlabs.directserve.core.resources.ui.GhostSpacing
import com.ghostgramlabs.directserve.core.resources.util.LocalizationUtils
import com.ghoststream.core.model.ConnectionDiagnostics
import com.ghoststream.core.model.DeviceNameGenerator
import com.ghoststream.core.model.LibraryState
import com.ghoststream.core.model.NearbyDevice
import com.ghoststream.core.model.NearbyDiscoveryState
import com.ghoststream.core.model.NetworkType
import com.ghoststream.core.model.RecentSession

import com.ghoststream.core.model.SessionState
import com.ghoststream.core.model.deviceIdentity

@OptIn(ExperimentalLayoutApi::class)
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

    LaunchedEffect(settings) {
        if (!settings.shareVideos && !settings.shareMusic && !settings.sharePhotos && !settings.shareFiles) {
            onToggleShareVideos(true)
            onToggleShareMusic(true)
            onToggleSharePhotos(true)
            onToggleShareFiles(true)
        }
    }


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

    val appBackground = MaterialTheme.colorScheme.background
    val hasLibraryContent = libraryState.items.isNotEmpty() || libraryState.folders.isNotEmpty()

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
                isLiveScreenActive = isLiveScreenActive,
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
                enabled = !isLiveScreenActive,
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
                hasLibraryContent = hasLibraryContent,
                isSharing = sessionState.isSharing,
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
private fun FeatureSections(
    settings: com.ghoststream.core.model.AppSettings,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenLiveScreen: () -> Unit,
    onOpenQuickText: () -> Unit,
    onOpenDlna: () -> Unit,
    isLiveScreenActive: Boolean,
    hasLibraryContent: Boolean,
    isSharing: Boolean,
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
            isAccent = hasLibraryContent,
        )

        Spacer(modifier = Modifier.height(40.dp))

        Text(
            text = stringResource(R.string.home_section_live_tools),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        FlatFeatureRow(
            icon = Icons.Outlined.LiveTv,
            title = stringResource(R.string.home_feature_live_screen),
            detail = stringResource(R.string.home_feature_live_screen_desc),
            onClick = onOpenLiveScreen,
            isAccent = isLiveScreenActive,
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
                isAccent = settings.quickTextEnabled && isSharing,
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
            isAccent = settings.dlnaEnabled,
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
        val containerColor = if (isAccent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        val iconTint = if (isAccent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
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
private fun TopBrandHeader(
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = GhostSpacing.screenHorizontal,
                vertical = GhostSpacing.headerVertical,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.home_brand_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.home_brand_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onOpenHistory),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.History,
                        contentDescription = stringResource(R.string.home_history_content_desc),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onOpenSettings),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.home_settings_content_desc),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    isLiveScreenActive: Boolean,
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
            enabled = !isStartingShare && !isLiveScreenActive,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
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

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onOpenLibrary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            ) {
                Text(
                    text = if (hasLibraryContent) stringResource(R.string.home_button_add_more) else stringResource(R.string.home_button_add_media),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(
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
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
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
private fun ConnectedDevicesCard(
    connectedClients: List<com.ghoststream.core.model.ConnectedClient>,
    deviceNicknames: Map<String, String>,
    onSaveDeviceNickname: (String, String) -> Unit,
) {
    var editingClientId by remember { mutableStateOf<String?>(null) }
    var editNickname by remember { mutableStateOf("") }

    Card(
        modifier = Modifier
            .padding(horizontal = GhostSpacing.screenHorizontal)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(GhostSpacing.card)) {
            Text(stringResource(R.string.home_connected_devices), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(12.dp))

            connectedClients.forEach { client ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val identity = deviceIdentity(client.ipAddress)
                    val customName = deviceNicknames[client.ipAddress]
                    val displayName = customName ?: identity.generatedName
                    Column(modifier = Modifier.weight(1f)) {
                        Text(displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(identity.ipAddress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(
                        onClick = { 
                            editingClientId = client.id
                            editNickname = customName ?: ""
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.common_edit_nickname), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    // Nickname edit dialog
    if (editingClientId != null) {
        val editingClient = connectedClients.find { it.id == editingClientId }
        val defaultName = if (editingClient != null) deviceIdentity(editingClient.ipAddress).generatedName else ""
        val defaultIp = editingClient?.ipAddress.orEmpty()
        
        AlertDialog(
            onDismissRequest = { editingClientId = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 0.dp,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text(stringResource(R.string.home_rename_device)) },
            text = {
                Column {
                    Text(stringResource(R.string.home_rename_device_subtitle, defaultName, defaultIp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, softWrap = true)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editNickname,
                        onValueChange = { editNickname = it },
                        label = { Text(stringResource(R.string.home_rename_custom_name)) },
                        placeholder = { Text(defaultName) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clientIp = connectedClients.find { it.id == editingClientId }?.ipAddress
                        if (clientIp != null) {
                            onSaveDeviceNickname(clientIp, editNickname)
                        }
                        editingClientId = null
                    }
                ) {
                    Text(stringResource(R.string.home_rename_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingClientId = null }) {
                    Text(stringResource(R.string.home_rename_cancel))
                }
            },
        )
    }
}

@Composable
private fun QuickDateFiltersCard(
    onOpenLibrary: () -> Unit,
    onOpenDateRangePicker: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = GhostSpacing.screenHorizontal)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(GhostSpacing.card)) {
            Text(stringResource(R.string.home_quick_filters), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.home_quick_filters_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            BoxWithConstraints {
                val tileWidth = (maxWidth - 8.dp) / 2
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateFilterTile(
                            label = stringResource(R.string.home_quick_filter_today),
                            detail = stringResource(R.string.home_quick_filter_today_desc),
                            icon = Icons.Outlined.Schedule,
                            onClick = onOpenLibrary,
                            modifier = Modifier.width(tileWidth),
                        )
                        DateFilterTile(
                            label = stringResource(R.string.home_quick_filter_week),
                            detail = stringResource(R.string.home_quick_filter_week_desc),
                            icon = Icons.Outlined.Schedule,
                            onClick = onOpenLibrary,
                            modifier = Modifier.width(tileWidth),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateFilterTile(
                            label = stringResource(R.string.home_quick_filter_month),
                            detail = stringResource(R.string.home_quick_filter_month_desc),
                            icon = Icons.Outlined.Schedule,
                            onClick = onOpenLibrary,
                            modifier = Modifier.width(tileWidth),
                        )
                        DateFilterTile(
                            label = stringResource(R.string.home_quick_filter_custom),
                            detail = stringResource(R.string.home_quick_filter_custom_desc),
                            icon = Icons.Outlined.Schedule,
                            onClick = onOpenDateRangePicker,
                            modifier = Modifier.width(tileWidth),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DateFilterTile(
    label: String,
    detail: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (pressed) 0.97f else 1f, label = "dateFilterScale")
    val containerColor by animateColorAsState(
        targetValue = if (pressed) ghostAccentSurface() else MaterialTheme.colorScheme.surfaceVariant,
        label = "dateFilterColor",
    )
    Card(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(ghostAccentSurface(), RoundedCornerShape(8.dp))
                    .border(BorderStroke(1.dp, ghostAccentBorder()), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DateRangePickerDialog(
    startDateMillis: Long,
    endDateMillis: Long,
    onStartDateChanged: (Long) -> Unit,
    onEndDateChanged: (Long) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sdf = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
    val startDateStr = if (startDateMillis > 0) sdf.format(java.util.Date(startDateMillis)) else stringResource(R.string.home_date_range_start)
    val endDateStr = if (endDateMillis > 0) sdf.format(java.util.Date(endDateMillis)) else stringResource(R.string.home_date_range_end)
    var startDateInput by remember { mutableStateOf(startDateStr) }
    var endDateInput by remember { mutableStateOf(endDateStr) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text(stringResource(R.string.home_date_range_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.home_date_range_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = startDateInput,
                        onValueChange = { startDateInput = it },
                        label = { Text(stringResource(R.string.home_date_range_from)) },
                        placeholder = { Text(stringResource(R.string.home_date_range_start)) },
                        readOnly = true,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // In a real implementation, open a date picker
                                // For now, users can manually type or use the system
                            },
                    )
                    OutlinedTextField(
                        value = endDateInput,
                        onValueChange = { endDateInput = it },
                        label = { Text(stringResource(R.string.home_date_range_to)) },
                        placeholder = { Text(stringResource(R.string.home_date_range_end)) },
                        readOnly = true,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // In a real implementation, open a date picker
                                // For now, users can manually type or use the system
                            },
                    )
                }
                Text(
                    stringResource(R.string.home_date_range_tip),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(16.dp),
                colors = ghostPrimaryButtonColors(),
            ) {
                Text(stringResource(R.string.home_date_range_apply))
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(16.dp),
                colors = ghostSecondaryButtonColors(),
            ) {
                Text(stringResource(R.string.home_date_range_cancel))
            }
        },
    )
}

@Composable
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
            OutlinedButton(
                onClick = onRefreshNearby,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text(stringResource(R.string.common_refresh), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
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
private fun MinimalChip(
    label: String,
    showAccentDot: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showAccentDot) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp)),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatusChip(sessionState: SessionState) {
    val label = when {
        sessionState.isSharing -> stringResource(R.string.home_chip_sharing)
        sessionState.networkAvailability.isWifiOrHotspotReady -> stringResource(R.string.home_chip_ready)
        else -> stringResource(R.string.home_chip_setup_needed)
    }
    MinimalChip(
        label = label,
        showAccentDot = sessionState.isSharing || sessionState.networkAvailability.isWifiOrHotspotReady,
    )
}

@Composable
private fun HeroStat(label: String, value: String, highlight: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (highlight) ghostAccentSurface() else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (highlight) ghostAccentBorder() else MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ContentFilterRow(
    settings: com.ghoststream.core.model.AppSettings,
    libraryState: LibraryState,
    onToggleVideos: (Boolean) -> Unit,
    onToggleMusic: (Boolean) -> Unit,
    onTogglePhotos: (Boolean) -> Unit,
    onToggleFiles: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        containerColor = Color.Transparent,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        iconColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.home_content_filter_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = settings.shareVideos,
                onClick = { onToggleVideos(!settings.shareVideos) },
                label = { Text("${stringResource(R.string.home_content_filter_videos)} (${libraryState.summary.videos})") },
                leadingIcon = { Icon(Icons.Outlined.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp)) },
                colors = chipColors,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = settings.shareMusic,
                onClick = { onToggleMusic(!settings.shareMusic) },
                label = { Text("${stringResource(R.string.home_content_filter_music)} (${libraryState.summary.music})") },
                leadingIcon = { Icon(Icons.Outlined.LibraryMusic, contentDescription = null, modifier = Modifier.size(18.dp)) },
                colors = chipColors,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = settings.sharePhotos,
                onClick = { onTogglePhotos(!settings.sharePhotos) },
                label = { Text("${stringResource(R.string.home_content_filter_photos)} (${libraryState.summary.photos})") },
                leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp)) },
                colors = chipColors,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = settings.shareFiles,
                onClick = { onToggleFiles(!settings.shareFiles) },
                label = { Text("${stringResource(R.string.home_content_filter_files)} (${libraryState.summary.files})") },
                leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(18.dp)) },
                colors = chipColors,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
        }
    }


}

@Composable
private fun PulsingLiveDot(
    dotColor: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "homeLivePulse")
    val ringScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 2.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "homeLivePulseRingScale",
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "homeLivePulseRingAlpha",
    )
    val coreScale by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "homeLivePulseCoreScale",
    )
    Box(
        modifier = modifier.size(34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(13.dp)
                .graphicsLayer {
                    scaleX = ringScale
                    scaleY = ringScale
                    alpha = ringAlpha
                }
                .background(dotColor, RoundedCornerShape(999.dp)),
        )
        Box(
            modifier = Modifier
                .size(13.dp)
                .graphicsLayer {
                    scaleX = coreScale
                    scaleY = coreScale
                }
                .background(dotColor, RoundedCornerShape(999.dp)),
        )
    }
}

@Composable
private fun ghostPrimaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f),
)

@Composable
private fun ghostSecondaryButtonColors() = ButtonDefaults.outlinedButtonColors(
    containerColor = Color.Transparent,
    contentColor = MaterialTheme.colorScheme.primary,
    disabledContainerColor = Color.Transparent,
    disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.46f),
)

@Composable
private fun ghostAccentSurface() = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)

@Composable
private fun ghostAccentBorder() = MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)

@Composable
private fun ghostLiveSurface() = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)

@Composable
private fun ghostLiveBorder() = MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)

@Composable
private fun heroMessage(
    sessionState: SessionState,
    libraryState: LibraryState,
    diagnostics: ConnectionDiagnostics,
): String {
    return when {
        sessionState.isSharing -> stringResource(R.string.home_body_live)
        libraryState.summary.totalItems == 0 && libraryState.folders.isEmpty() -> stringResource(R.string.home_body_start)
        diagnostics.actionCount > 0 -> stringResource(R.string.network_setup_body)
        diagnostics.warningCount > 0 -> stringResource(R.string.home_body_start_secondary)
        else -> stringResource(R.string.home_body_start_secondary)
    }
}



@Composable
private fun LiveStatusIndicator(
    sessionState: SessionState,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (sessionState.isSharing) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, if (sessionState.isSharing) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (sessionState.isSharing) {
                PulsingLiveDot(dotColor = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(6.dp))
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
}
