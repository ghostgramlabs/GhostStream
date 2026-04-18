package com.ghoststream.feature.home

import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.ghostgramlabs.directserve.core.resources.R
import com.ghostgramlabs.directserve.core.resources.ui.GhostSpacing
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
    libraryState: LibraryState,
    sessionState: SessionState,
    recentSessions: List<RecentSession>,

    connectionDiagnostics: ConnectionDiagnostics,
    nearbyDiscoveryState: NearbyDiscoveryState,
    connectingNearbyDeviceId: String?,
    pendingUploadRequest: com.ghoststream.core.model.UploadRequest?,
    isStartingShare: Boolean,
    onStartSharing: () -> Unit,

    onRefreshConnection: () -> Unit,
    onRefreshNearby: () -> Unit,
    onOpenNearbyDevice: (NearbyDevice) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onResolveUploadRequest: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(vertical = GhostSpacing.screenVertical),
        verticalArrangement = Arrangement.spacedBy(GhostSpacing.section),
    ) {
        item {
            TopBrandHeader(
                onOpenSettings = onOpenSettings,
                onOpenHistory = onOpenHistory
            )
        }

        item {
            SessionHeroCard(
                libraryState = libraryState,
                sessionState = sessionState,
                connectionDiagnostics = connectionDiagnostics,
                isStartingShare = isStartingShare,
                onOpenLibrary = onOpenLibrary,
                onStartSharing = onStartSharing,
            )
        }

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
            item {
                RecentSessionsCard(recentSessions = recentSessions)
            }
        }

        item { Spacer(modifier = Modifier.height(4.dp)) }
    }

    pendingUploadRequest?.let { request ->
        val requesterIdentity = deviceIdentity(request.requesterIp)
        AlertDialog(
            onDismissRequest = { onResolveUploadRequest(request.id, false) },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        stringResource(R.string.home_upload_request_total_size, formatBytes(request.sizeBytes)),
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
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = ghostAccentSurface(),
                border = BorderStroke(1.dp, ghostAccentBorder()),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onOpenHistory),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.History,
                        contentDescription = stringResource(R.string.home_history_content_desc),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = ghostAccentSurface(),
                border = BorderStroke(1.dp, ghostAccentBorder()),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onOpenSettings),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.home_settings_content_desc),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionHeroCard(
    libraryState: LibraryState,
    sessionState: SessionState,
    connectionDiagnostics: ConnectionDiagnostics,
    isStartingShare: Boolean,
    onOpenLibrary: () -> Unit,
    onStartSharing: () -> Unit,
) {
    val canStartSession = sessionState.networkAvailability.isWifiOrHotspotReady
    var showNoNetworkDialog by rememberSaveable { mutableStateOf(false) }
    
    val heroContainerColor by animateColorAsState(
        targetValue = if (sessionState.isSharing) ghostLiveSurface() else MaterialTheme.colorScheme.surfaceVariant,
        label = "homeHeroContainer",
    )
    val heroBorderColor by animateColorAsState(
        targetValue = if (sessionState.isSharing) ghostLiveBorder() else MaterialTheme.colorScheme.outline,
        label = "homeHeroBorder",
    )
    
    // Show error dialog if no network
    if (showNoNetworkDialog) {
        AlertDialog(
            onDismissRequest = { showNoNetworkDialog = false },
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
    
    Card(
        modifier = Modifier
            .padding(horizontal = GhostSpacing.screenHorizontal)
            .fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = heroContainerColor),
        border = BorderStroke(1.dp, heroBorderColor),
    ) {
        Column(
            modifier = Modifier
                .padding(GhostSpacing.heroCard),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MinimalChip(label = stringResource(R.string.home_chip_local_session))
                StatusChip(sessionState = sessionState)
            }

            Spacer(modifier = Modifier.height(18.dp))
            if (sessionState.isSharing) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = ghostLiveSurface(),
                    border = BorderStroke(1.dp, ghostLiveBorder()),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp)),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.home_live_banner),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
            }
            if (sessionState.isSharing) {
                Text(
                    text = stringResource(R.string.home_title_live),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = heroMessage(
                        sessionState = sessionState,
                        libraryState = libraryState,
                        diagnostics = connectionDiagnostics,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = true,
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
                    border = BorderStroke(1.dp, ghostAccentBorder()),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        MinimalChip(label = stringResource(R.string.home_summary_current_share), showAccentDot = true)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.home_title_start),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${stringResource(R.string.home_body_start)} ${stringResource(R.string.home_body_start_secondary)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            softWrap = true,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onOpenLibrary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ghostPrimaryButtonColors(),
                ) {
                    Text(
                        text = if (libraryState.summary.totalItems > 0 || libraryState.folders.isNotEmpty()) {
                            stringResource(R.string.home_button_add_more)
                        } else {
                            stringResource(R.string.home_button_add_media)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = {
                        if (sessionState.isSharing || canStartSession) {
                            onStartSharing()
                        } else {
                            showNoNetworkDialog = true
                        }
                    },
                    enabled = !isStartingShare,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ghostSecondaryButtonColors(),
                ) {
                    if (isStartingShare) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.home_button_starting),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            if (sessionState.isSharing) stringResource(R.string.home_button_open_session) else stringResource(R.string.home_button_start_session),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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

            Spacer(modifier = Modifier.height(18.dp))
            SummaryStrip(libraryState = libraryState)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryStrip(libraryState: LibraryState) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HeroStat(
            label = stringResource(R.string.home_summary_current_share),
            value = libraryState.summary.totalItems.toString(),
            highlight = libraryState.summary.totalItems > 0 || libraryState.folders.isNotEmpty(),
        )
        HeroStat(label = stringResource(R.string.home_summary_media), value = (libraryState.summary.videos + libraryState.summary.photos + libraryState.summary.music).toString())
        HeroStat(label = stringResource(R.string.library_info_size), value = formatBytes(libraryState.summary.totalBytes))
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
        containerColor = MaterialTheme.colorScheme.surface,
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

@OptIn(ExperimentalLayoutApi::class)
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
    Card(
        modifier = Modifier
            .padding(horizontal = GhostSpacing.screenHorizontal)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(GhostSpacing.card),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.home_nearby_open_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onRefreshNearby,
                    shape = RoundedCornerShape(16.dp),
                    colors = ghostSecondaryButtonColors(),
                ) {
                    Text(stringResource(R.string.common_refresh), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (nearbyDiscoveryState.devices.isEmpty()) {
                Text(
                    stringResource(R.string.home_nearby_optional_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = true,
                )
            } else {
                nearbyDiscoveryState.devices.take(3).forEach { device ->
                    NearbyDeviceRow(
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
private fun SupportRow(
    icon: ImageVector,
    title: String,
    detail: String,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .background(ghostAccentSurface(), RoundedCornerShape(12.dp))
                    .border(BorderStroke(1.dp, ghostAccentBorder()), RoundedCornerShape(12.dp))
                    .padding(10.dp),
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NearbyDeviceRow(
    device: NearbyDevice,
    isConnecting: Boolean,
    onOpen: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            val stacked = maxWidth < 460.dp
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NearbyDeviceSummary(device = device)
                    Button(
                        onClick = onOpen,
                        enabled = !isConnecting,
                        modifier = Modifier.fillMaxWidth(),
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
                            Text(stringResource(R.string.home_nearby_action_open_session))
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NearbyDeviceSummary(device = device, modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(12.dp))
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
                .joinToString(" | "),
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
private fun RecentSessionsCard(
    recentSessions: List<RecentSession>,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = GhostSpacing.screenHorizontal)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(GhostSpacing.card),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.home_recent_shares),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            recentSessions.take(3).forEach { session ->
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.home_saved_shares_items_count, session.totalItems), style = MaterialTheme.typography.titleMedium)
                        Text(
                            DateUtils.getRelativeTimeSpanString(session.endedAtEpochMs).toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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

@Composable
private fun ghostPrimaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f),
)

@Composable
private fun ghostSecondaryButtonColors() = ButtonDefaults.outlinedButtonColors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f),
)

@Composable
private fun ghostAccentSurface() = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)

@Composable
private fun ghostAccentBorder() = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)

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

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (value >= 100 || unitIndex == 0) {
        "${value.toInt()} ${units[unitIndex]}"
    } else {
        String.format("%.1f %s", value, units[unitIndex])
    }
}
