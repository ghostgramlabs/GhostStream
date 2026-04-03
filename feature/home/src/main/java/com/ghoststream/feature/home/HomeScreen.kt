package com.ghoststream.feature.home

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghoststream.core.model.LibraryState
import com.ghoststream.core.model.NetworkType
import com.ghoststream.core.model.RecentSession
import com.ghoststream.core.model.SessionState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    libraryState: LibraryState,
    sessionState: SessionState,
    recentSessions: List<RecentSession>,
    onStartSharing: () -> Unit,
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onBatchSelect: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenRecentShares: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF04070C), Color(0xFF0D1421), Color(0xFF06080D)),
                ),
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                Text(
                    text = "GhostStream",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Offline media sharing",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101826)),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (sessionState.isSharing) "Sharing is live" else "Stream & share files offline",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = when {
                                    sessionState.isSharing -> "Nearby devices can scan the QR code and start playing in a browser."
                                    sessionState.networkAvailability.isReady -> "No internet needed. Add content and start a local sharing session."
                                    else -> "Connect both devices to the same Wi-Fi or hotspot."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusChip(sessionState = sessionState)
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = onStartSharing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF87E6FF),
                            contentColor = Color(0xFF001A24),
                        ),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Start Sharing", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B121D)),
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF162435),
                    ) {
                        Box(modifier = Modifier.padding(12.dp)) {
                            Icon(Icons.Outlined.NetworkCheck, contentDescription = null, tint = Color(0xFF8AE3FF))
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = when (sessionState.networkAvailability.type) {
                                NetworkType.WIFI -> "Wi-Fi connected"
                                NetworkType.HOTSPOT -> "Hotspot active"
                                NetworkType.LOCAL -> "Local network ready"
                                NetworkType.NONE -> "No local network"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = sessionState.networkAvailability.helperText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            FlowRow(
                modifier = Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickActionButton(label = "Add Files", icon = Icons.Outlined.AddBox, onClick = onAddFiles)
                QuickActionButton(label = "Add Folder", icon = Icons.Outlined.FolderOpen, onClick = onAddFolder)
                QuickActionButton(label = "Batch Select", icon = Icons.Outlined.Collections, onClick = onBatchSelect)
                QuickActionButton(label = "Recent Shares", icon = Icons.Outlined.History, onClick = onOpenRecentShares)
                OutlinedButton(
                    onClick = onOpenLibrary,
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("Shared Library")
                }
            }
        }

        item {
            FlowRow(
                modifier = Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SummaryCard("Videos", libraryState.summary.videos.toString())
                SummaryCard("Photos", libraryState.summary.photos.toString())
                SummaryCard("Music", libraryState.summary.music.toString())
                SummaryCard("Files", libraryState.summary.files.toString())
                SummaryCard("Selected", libraryState.summary.totalItems.toString())
                SummaryCard("Size", formatBytes(libraryState.summary.totalBytes))
            }
        }

        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1018)),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = if (recentSessions.isEmpty()) "Quick tips" else "Recent sessions",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    if (recentSessions.isEmpty()) {
                        Text(
                            text = "Add a folder to share albums fast, then open Start Sharing when both devices are nearby.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        recentSessions.take(3).forEach { session ->
                            Text(
                                text = "${session.totalItems} items shared ${DateUtils.getRelativeTimeSpanString(session.endedAtEpochMs)}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatusChip(sessionState: SessionState) {
    val label = when {
        sessionState.isSharing -> "Sharing"
        sessionState.networkAvailability.isReady -> "Not sharing"
        else -> "Network required"
    }
    AssistChip(
        onClick = {},
        label = { Text(label) },
    )
}

@Composable
private fun QuickActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
    ) {
        Icon(icon, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun SummaryCard(label: String, value: String) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101826)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
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
