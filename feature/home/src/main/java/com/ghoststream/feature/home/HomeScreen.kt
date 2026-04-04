package com.ghoststream.feature.home

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghoststream.core.model.ConnectionDiagnostics
import com.ghoststream.core.model.LibraryState
import com.ghoststream.core.model.NearbyDevice
import com.ghoststream.core.model.NearbyDiscoveryState
import com.ghoststream.core.model.NetworkType
import com.ghoststream.core.model.RecentSession
import com.ghoststream.core.model.SharePreset
import com.ghoststream.core.model.SessionState

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
    isStartingShare: Boolean,
    onStartSharing: () -> Unit,
    onSavePreset: (String) -> Unit,
    onApplyPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onRefreshDiagnostics: () -> Unit,
    onOpenNearbyDevice: (NearbyDevice) -> Unit,
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onBatchSelect: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPresetDialog by rememberSaveable { mutableStateOf(false) }
    var presetName by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF050607), Color(0xFF0A0D10), Color(0xFF0E1317)),
                ),
            ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            TopBrandHeader(onOpenSettings = onOpenSettings)
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
                onBatchSelect = onBatchSelect,
                onOpenLibrary = onOpenLibrary,
            )
        }

        item {
            SupportPanel(
                sessionState = sessionState,
                connectionDiagnostics = connectionDiagnostics,
                nearbyDiscoveryState = nearbyDiscoveryState,
                connectingNearbyDeviceId = connectingNearbyDeviceId,
                onRefreshDiagnostics = onRefreshDiagnostics,
                onOpenNearbyDevice = onOpenNearbyDevice,
            )
        }

        if (sharePresets.isNotEmpty() || libraryState.summary.totalItems > 0) {
            item {
                SharePresetsCard(
                    presets = sharePresets,
                    canSavePreset = libraryState.summary.totalItems > 0,
                    onSavePreset = {
                        presetName = libraryState.folders.firstOrNull()?.displayName
                            ?: if (libraryState.summary.videos > 0) "Movie Night" else "My Share"
                        showPresetDialog = true
                    },
                    onApplyPreset = onApplyPreset,
                    onDeletePreset = onDeletePreset,
                )
            }
        }

        item {
            RecentSessionsCard(recentSessions = recentSessions)
        }

        item {
            Spacer(modifier = Modifier.height(18.dp))
        }
    }

    if (showPresetDialog) {
        AlertDialog(
            onDismissRequest = { showPresetDialog = false },
            title = { Text("Save Current Library") },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text("Saved share name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSavePreset(presetName)
                        showPresetDialog = false
                    },
                    enabled = presetName.isNotBlank(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPresetDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun TopBrandHeader(
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "GHOSTSTREAM: SHARE & STREAM",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Private local streaming",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Scan, open, play. No internet. No cloud.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF11161A),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
            modifier = Modifier.clickable(onClick = onOpenSettings),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Settings", style = MaterialTheme.typography.labelLarge)
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
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1417)),
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF11191B), Color(0xFF0F1417)),
                    ),
                )
                .padding(24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text("Local session") },
                )
                StatusChip(sessionState = sessionState)
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = if (sessionState.isSharing) "Sharing is live" else "Turn this phone into a private media lounge",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
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
            )

            Spacer(modifier = Modifier.height(22.dp))
            Button(
                onClick = onStartSharing,
                enabled = !isStartingShare,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (isStartingShare) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Starting sharing", style = MaterialTheme.typography.titleMedium)
                } else {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Start Sharing", style = MaterialTheme.typography.titleMedium)
                }
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
        HeroStat(label = "Items", value = libraryState.summary.totalItems.toString())
        HeroStat(label = "Videos", value = libraryState.summary.videos.toString())
        HeroStat(label = "Photos", value = libraryState.summary.photos.toString())
        HeroStat(label = "Size", value = formatBytes(libraryState.summary.totalBytes))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionShelf(
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onBatchSelect: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF11161A)),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Quick actions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Add content fast, open your library, or build a smart set in two taps.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            BoxWithConstraints {
                val tileWidth = (maxWidth - 12.dp) / 2
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionTile(
                            label = "Add files",
                            detail = "Pick anything",
                            icon = Icons.Outlined.AddBox,
                            onClick = onAddFiles,
                            modifier = Modifier.width(tileWidth),
                        )
                        ActionTile(
                            label = "Add folder",
                            detail = "Scan a folder",
                            icon = Icons.Outlined.FolderOpen,
                            onClick = onAddFolder,
                            modifier = Modifier.width(tileWidth),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionTile(
                            label = "Smart Picks",
                            detail = "Smart groups",
                            icon = Icons.Outlined.Collections,
                            onClick = onBatchSelect,
                            modifier = Modifier.width(tileWidth),
                        )
                        ActionTile(
                            label = "Shared library",
                            detail = "Curate content",
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
    Card(
        modifier = modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151B20)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Color(0xFF1C2628), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupportPanel(
    sessionState: SessionState,
    connectionDiagnostics: ConnectionDiagnostics,
    nearbyDiscoveryState: NearbyDiscoveryState,
    connectingNearbyDeviceId: String?,
    onRefreshDiagnostics: () -> Unit,
    onOpenNearbyDevice: (NearbyDevice) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF11161A)),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Session readiness", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        connectionDiagnostics.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onRefreshDiagnostics, shape = RoundedCornerShape(16.dp)) {
                    Text("Refresh")
                }
            }

            SupportRow(
                icon = Icons.Outlined.NetworkCheck,
                title = when (sessionState.networkAvailability.type) {
                    NetworkType.WIFI -> "Wi-Fi connected"
                    NetworkType.HOTSPOT -> "Hotspot active"
                    NetworkType.LOCAL -> "Local network ready"
                    NetworkType.NONE -> "Local network needed"
                },
                detail = sessionState.networkAvailability.helperText,
            )

            if (nearbyDiscoveryState.devices.isEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SupportRow(
                        icon = Icons.Outlined.OpenInBrowser,
                        title = "Browser link stays primary",
                        detail = "${nearbyDiscoveryState.helperText} Optional if the other device also has GhostStream.",
                    )
                    OutlinedButton(
                        onClick = onRefreshDiagnostics,
                        shape = RoundedCornerShape(16.dp),
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
                        Text("Nearby GhostStream devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        OutlinedButton(
                            onClick = onRefreshDiagnostics,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text("Refresh nearby")
                        }
                    }
                    Text(
                        "Optional shortcut. This is only useful when the receiving device also has GhostStream.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    nearbyDiscoveryState.devices.take(2).forEach { device ->
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
        color = Color(0xFF151B20),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
        color = Color(0xFF151B20),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device.serviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = device.friendlyUrl ?: "Browser-ready on this local network",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(onClick = {}, label = { Text("Available") })
                    if (device.authRequired) {
                        AssistChip(onClick = {}, label = { Text("PIN") })
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(onClick = onOpen, shape = RoundedCornerShape(16.dp), enabled = !isConnecting) {
                if (isConnecting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.OpenInBrowser, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open")
                }
            }
        }
    }
}

@Composable
private fun SharePresetsCard(
    presets: List<SharePreset>,
    canSavePreset: Boolean,
    onSavePreset: () -> Unit,
    onApplyPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF11161A)),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Saved Shares", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Save the current library here, or pick individual files from Shared Library.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = onSavePreset,
                    enabled = canSavePreset,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("Save this library")
                }
            }

            if (presets.isEmpty()) {
                Text(
                    text = "No saved shares yet. Save the current library when it feels right.",
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
        color = Color(0xFF151B20),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
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
                        OutlinedButton(onClick = onApply, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Text("Open saved share")
                        }
                        OutlinedButton(onClick = onDelete, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Text("Delete")
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onApply, shape = RoundedCornerShape(16.dp)) {
                            Text("Open saved share")
                        }
                        OutlinedButton(onClick = onDelete, shape = RoundedCornerShape(16.dp)) {
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
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF11161A)),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = if (recentSessions.isEmpty()) "Quick tip" else "Recent sessions",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (recentSessions.isEmpty()) {
                Text(
                    text = "For the cleanest first run, add a few files, check that Wi-Fi or hotspot is ready, then start sharing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                recentSessions.take(3).forEach { session ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Color(0xFF151B20),
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
}

@Composable
private fun StatusChip(sessionState: SessionState) {
    val label = when {
        sessionState.isSharing -> "Sharing"
        sessionState.networkAvailability.isReady -> "Ready"
        else -> "Setup needed"
    }
    AssistChip(onClick = {}, label = { Text(label) })
}

@Composable
private fun HeroStat(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF151B20),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun heroMessage(
    sessionState: SessionState,
    libraryState: LibraryState,
    diagnostics: ConnectionDiagnostics,
): String {
    return when {
        sessionState.isSharing -> "Nearby devices can join right now from the same Wi-Fi or hotspot."
        libraryState.summary.totalItems == 0 -> "Add files or a folder first, then launch a private browser session."
        diagnostics.actionCount > 0 -> "A quick setup step is still missing before nearby devices can connect."
        diagnostics.warningCount > 0 -> "Everything is close. A little prep now will make playback smoother."
        else -> "Your files stay on this phone while nearby browsers stream over your local network."
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
