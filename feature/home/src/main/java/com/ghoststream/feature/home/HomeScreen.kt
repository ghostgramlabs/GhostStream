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
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VideoLibrary
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghoststream.core.model.ConnectionDiagnostics
import com.ghoststream.core.model.DeviceNameGenerator
import com.ghoststream.core.model.LibraryState
import com.ghoststream.core.model.NearbyDevice
import com.ghoststream.core.model.NearbyDiscoveryState
import com.ghoststream.core.model.NetworkType
import com.ghoststream.core.model.RecentSession
import com.ghoststream.core.model.SharePreset
import com.ghoststream.core.model.SessionState
import com.ghoststream.core.model.deviceIdentity

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    libraryState: LibraryState,
    sessionState: SessionState,
    recentSessions: List<RecentSession>,
    sharePresets: List<SharePreset>,
    connectionDiagnostics: ConnectionDiagnostics,
    nearbyDiscoveryState: NearbyDiscoveryState,
    connectingNearbyDeviceId: String?,
    pendingUploadRequest: com.ghoststream.core.model.UploadRequest?,
    isStartingShare: Boolean,
    onStartSharing: () -> Unit,
    onSavePreset: (String) -> Unit,
    onApplyPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onRefreshConnection: () -> Unit,
    onRefreshNearby: () -> Unit,
    onOpenNearbyDevice: (NearbyDevice) -> Unit,
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
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
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
                onStartSharing = onStartSharing,
            )
        }

        item {
            ActionShelf(
                onAddFiles = onAddFiles,
                onAddFolder = onAddFolder,
                onOpenLibrary = onOpenLibrary,
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

        if (sharePresets.isNotEmpty() || libraryState.summary.totalItems > 0) {
            item {
                SharePresetsCard(
                    presets = sharePresets,
                    canSavePreset = libraryState.summary.totalItems > 0,
                    defaultPresetName = libraryState.folders.firstOrNull()?.displayName
                        ?: if (libraryState.summary.videos > 0) "Movie Night" else "My collection",
                    onSavePreset = onSavePreset,
                    onApplyPreset = onApplyPreset,
                    onDeletePreset = onDeletePreset,
                )
            }
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
            title = { Text("File transfer request") },
            text = {
                val fileText = if (request.fileCount > 1) "${request.fileCount} files" else "a file"
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${requesterIdentity.nameWithIp} wants to send you $fileText:",
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
                        "Total Size: ${formatBytes(request.sizeBytes)}",
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
                    Text("Accept")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { onResolveUploadRequest(request.id, false) },
                    shape = RoundedCornerShape(16.dp),
                    colors = ghostSecondaryButtonColors(),
                ) {
                    Text("Decline")
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
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "DirectServe",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Private sharing on your Wi-Fi or hotspot",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
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
                        contentDescription = "Transfer History",
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
                        contentDescription = "Settings",
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
            title = { Text("Network Connection Required") },
            text = { 
                Text(
                    "Please connect to Wi-Fi or turn on your hotspot to start a session.\n\n" +
                    "Mobile data alone cannot create a local session. Make sure both devices are on the same Wi-Fi network."
                )
            },
            confirmButton = {
                TextButton(onClick = { showNoNetworkDialog = false }) {
                    Text("Dismiss")
                }
            },
        )
    }
    
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = heroContainerColor),
        border = BorderStroke(1.dp, heroBorderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MinimalChip(label = "Local session")
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
                            text = "Session is actively live on your local network",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
            }
            Text(
                text = if (sessionState.isSharing) {
                    "Session is live"
                } else {
                    "Watch directly and share files on any nearby device"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (sessionState.isSharing) {
                    heroMessage(
                        sessionState = sessionState,
                        libraryState = libraryState,
                        diagnostics = connectionDiagnostics,
                    )
                } else {
                    "Watch videos instantly on TV, laptop, iPhone, iPad, Mac, Windows, Android, or any browser-enabled device"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!sessionState.isSharing) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Share files, watch directly, and send files back on the same Wi-Fi or hotspot. No internet required.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(22.dp))
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
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ghostPrimaryButtonColors(),
            ) {
                if (isStartingShare) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Starting...", style = MaterialTheme.typography.titleMedium)
                } else {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(if (sessionState.isSharing) "Open session" else "Start Session", style = MaterialTheme.typography.titleMedium)
                }
            }
            if (!sessionState.isSharing && !canStartSession) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "No Wi-Fi or hotspot detected",
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
        HeroStat(label = "Files", value = libraryState.summary.totalItems.toString())
        HeroStat(label = "Media", value = (libraryState.summary.videos + libraryState.summary.photos + libraryState.summary.music).toString())
        HeroStat(label = "Size", value = formatBytes(libraryState.summary.totalBytes))
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
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Connected devices", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
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
                        Text(displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(identity.ipAddress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(
                        onClick = { 
                            editingClientId = client.id
                            editNickname = customName ?: ""
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit nickname", modifier = Modifier.size(18.dp))
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
            title = { Text("Rename device") },
            text = {
                Column {
                    Text("Device: $defaultName • ${editingClient?.ipAddress}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editNickname,
                        onValueChange = { editNickname = it },
                        label = { Text("Custom name (optional)") },
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
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingClientId = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionShelf(
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Add to share", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Pick the files you want to send.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            BoxWithConstraints {
                val tileWidth = (maxWidth - 12.dp) / 3
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionTile(
                            label = "Files",
                            detail = "Choose",
                            icon = Icons.Outlined.AddBox,
                            onClick = onAddFiles,
                            modifier = Modifier.width(tileWidth),
                        )
                        ActionTile(
                            label = "Folder",
                            detail = "Scan",
                            icon = Icons.Outlined.FolderOpen,
                            onClick = onAddFolder,
                            modifier = Modifier.width(tileWidth),
                        )
                        ActionTile(
                            label = "Library",
                            detail = "Browse",
                            icon = Icons.Outlined.VideoLibrary,
                            onClick = onOpenLibrary,
                            modifier = Modifier.width(tileWidth),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionTile(
    label: String,
    detail: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (pressed) 0.97f else 1f, label = "actionTileScale")
    val containerColor by animateColorAsState(
        targetValue = if (pressed) ghostAccentSurface() else MaterialTheme.colorScheme.surfaceVariant,
        label = "actionTileColor",
    )
    Card(
        modifier = modifier
            .heightIn(min = 124.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(ghostAccentSurface(), RoundedCornerShape(12.dp))
                    .border(BorderStroke(1.dp, ghostAccentBorder()), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(3.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuickDateFiltersCard(
    onOpenLibrary: () -> Unit,
    onOpenDateRangePicker: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Quick filters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Add files from a specific date. Open library to see all your files.",
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
                            label = "Today",
                            detail = "Files from today",
                            icon = Icons.Outlined.Schedule,
                            onClick = onOpenLibrary,
                            modifier = Modifier.width(tileWidth),
                        )
                        DateFilterTile(
                            label = "This week",
                            detail = "Last 7 days",
                            icon = Icons.Outlined.Schedule,
                            onClick = onOpenLibrary,
                            modifier = Modifier.width(tileWidth),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateFilterTile(
                            label = "This month",
                            detail = "Last 30 days",
                            icon = Icons.Outlined.Schedule,
                            onClick = onOpenLibrary,
                            modifier = Modifier.width(tileWidth),
                        )
                        DateFilterTile(
                            label = "Date range",
                            detail = "From → To date",
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
    val startDateStr = if (startDateMillis > 0) sdf.format(java.util.Date(startDateMillis)) else "Select start date"
    val endDateStr = if (endDateMillis > 0) sdf.format(java.util.Date(endDateMillis)) else "Select end date"
    var startDateInput by remember { mutableStateOf(startDateStr) }
    var endDateInput by remember { mutableStateOf(endDateStr) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text("Select date range") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Choose files from a specific date range.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = startDateInput,
                        onValueChange = { startDateInput = it },
                        label = { Text("From date") },
                        placeholder = { Text("Select start date") },
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
                        label = { Text("To date") },
                        placeholder = { Text("Select end date") },
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
                    "Tip: Open the library to filter by these dates",
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
                Text("Apply filter")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(16.dp),
                colors = ghostSecondaryButtonColors(),
            ) {
                Text("Cancel")
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
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Nearby & network", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Make sure your local network is ready, then look for another DirectServe device if you want to open it in the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = onRefreshConnection,
                    shape = RoundedCornerShape(16.dp),
                    colors = ghostSecondaryButtonColors(),
                ) {
                    Text("Refresh status")
                }
            }

            SupportRow(
                icon = Icons.Outlined.NetworkCheck,
                title = when (sessionState.networkAvailability.type) {
                    NetworkType.WIFI -> "Wi-Fi connected"
                    NetworkType.HOTSPOT -> "Hotspot active"
                    NetworkType.LOCAL -> "Local network ready"
                    NetworkType.NONE -> "Network needed"
                },
                detail = when {
                    sessionState.networkAvailability.isReady -> "Nearby devices can open the link on this network."
                    else -> "Connect both devices to the same Wi-Fi or hotspot."
                },
            )

            if (nearbyDiscoveryState.devices.isEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SupportRow(
                        icon = Icons.Outlined.OpenInBrowser,
                        title = "DirectServe app is optional",
                        detail = "If the other device also has DirectServe, it can appear here. Everyone else can still scan the QR code or open the browser link.",
                    )
                    OutlinedButton(
                        onClick = onRefreshNearby,
                        shape = RoundedCornerShape(16.dp),
                        colors = ghostSecondaryButtonColors(),
                    ) {
                        Text("Refresh nearby")
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Open in the DirectServe app",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onRefreshNearby,
                            shape = RoundedCornerShape(16.dp),
                            colors = ghostSecondaryButtonColors(),
                        ) {
                            Text("Refresh nearby")
                        }
                    }
                    Text(
                        "This is only for devices with DirectServe installed. The browser link and QR code still work for everyone else.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                            Text("Open nearby session")
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
                            Text("Open")
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
            MinimalChip(label = "Available", showAccentDot = true)
            if (device.authRequired) {
                MinimalChip(label = "PIN needed")
            }
        }
    }
}

@Composable
private fun SharePresetsCard(
    presets: List<SharePreset>,
    canSavePreset: Boolean,
    defaultPresetName: String,
    onSavePreset: (String) -> Unit,
    onApplyPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Saved shares", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Save this share as \"$defaultPresetName\" to reuse it later.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { onSavePreset(defaultPresetName) },
                    enabled = canSavePreset,
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ghostPrimaryButtonColors(),
                ) {
                    Text("Save now")
                }
            }

            if (presets.isEmpty()) {
                Text(
                    text = "Nothing saved yet. Save your current selection above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                presets.take(3).forEach { preset ->
                    SharePresetRow(
                        preset = preset,
                        onApply = { onApplyPreset(preset.id) },
                        onDelete = { onDeletePreset(preset.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SharePresetRow(
    preset: SharePreset,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(preset.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "${preset.itemCount} items | ${formatBytes(preset.totalBytes)}" +
                    (preset.lastUsedAtEpochMs?.let { " | Used ${DateUtils.getRelativeTimeSpanString(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BoxWithConstraints {
                val stacked = maxWidth < 280.dp
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onApply,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ghostSecondaryButtonColors(),
                        ) {
                            Text("Open")
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ghostSecondaryButtonColors(),
                        ) {
                            Text("Delete")
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onApply,
                            shape = RoundedCornerShape(16.dp),
                            colors = ghostSecondaryButtonColors(),
                        ) {
                            Text("Open")
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            shape = RoundedCornerShape(16.dp),
                            colors = ghostSecondaryButtonColors(),
                        ) {
                            Text("Delete")
                        }
                    }
                }
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
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Recent shares",
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
                        Text("${session.totalItems} items", style = MaterialTheme.typography.titleMedium)
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
            )
        }
    }
}

@Composable
private fun StatusChip(sessionState: SessionState) {
    val label = when {
        sessionState.isSharing -> "Sharing"
        sessionState.networkAvailability.isWifiOrHotspotReady -> "Ready"
        else -> "Setup needed"
    }
    MinimalChip(
        label = label,
        showAccentDot = sessionState.isSharing || sessionState.networkAvailability.isWifiOrHotspotReady,
    )
}

@Composable
private fun HeroStat(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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

private fun heroMessage(
    sessionState: SessionState,
    libraryState: LibraryState,
    diagnostics: ConnectionDiagnostics,
): String {
    return when {
        sessionState.isSharing -> "Your session is live. Sharing and receiving is ready."
        libraryState.summary.totalItems == 0 -> "Start a session to send or receive files over Wi-Fi or hotspot."
        diagnostics.actionCount > 0 -> "Connect both devices to the same Wi-Fi or hotspot."
        diagnostics.warningCount > 0 -> "Everything is almost ready."
        else -> "Ready for wireless sending and receiving."
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
