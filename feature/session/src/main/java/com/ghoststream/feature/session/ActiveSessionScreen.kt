package com.ghoststream.feature.session

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghoststream.core.model.BlockedClient
import com.ghoststream.core.model.ConnectedClient
import com.ghoststream.core.model.SessionState
import com.ghoststream.core.model.displayAccessUrl
import com.ghoststream.core.model.resolvedAccessUrl
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActiveSessionScreen(
    sessionState: SessionState,
    hapticOnDeviceConnect: Boolean,
    onCopyLink: () -> Unit,
    onShareLink: () -> Unit,
    onStopSharing: () -> Unit,
    onBlockClient: (String) -> Unit,
    onUnblockClient: (String) -> Unit,
    onRegeneratePin: () -> Unit,
    onDisconnectAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val accessUrl = remember(sessionState.sessionUrl, sessionState.networkAvailability.localAddress, sessionState.serverPort) {
        sessionState.resolvedAccessUrl()
    }
    val displayUrl = remember(
        sessionState.hostname,
        sessionState.sessionUrl,
        sessionState.networkAvailability.localAddress,
        sessionState.serverPort,
    ) {
        sessionState.displayAccessUrl()
    }
    LaunchedEffect(sessionState.connectedClients.size, hapticOnDeviceConnect) {
        if (hapticOnDeviceConnect && sessionState.connectedClients.isNotEmpty()) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF07080C), Color(0xFF10151C), Color(0xFF0A0C11)),
                ),
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 20.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF121823)),
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (sessionState.isSharing) "Private session live" else "Preparing browser access",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (sessionState.isSharing) {
                                    "Scan the QR code or open the local link on a nearby browser."
                                } else {
                                    sessionState.message
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    when {
                                        sessionState.isSharing -> "Live"
                                        sessionState.networkAvailability.isReady -> "Starting"
                                        else -> "Network required"
                                    },
                                )
                            },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            accessUrl?.let { url ->
                                val qrBitmap = remember(url) { generateQrBitmap(url) }
                                qrBitmap?.let {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = "Session QR",
                                        modifier = Modifier.size(220.dp),
                                    )
                                }
                            } ?: Text(
                                text = "Preparing QR",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SessionHighlight(
                                title = if (sessionState.hostname != null) "Friendly local link" else "Access link",
                                value = displayUrl ?: accessUrl ?: "Waiting for local link",
                                supporting = sessionState.advertisedName?.let { "Nearby GhostStream apps will see $it." }
                                    ?: "QR and Share use the most reliable local browser path.",
                            )
                            SessionHighlight(
                                title = "Session security",
                                value = if (sessionState.authEnabled) "PIN ${sessionState.pin ?: "----"}" else "PIN off",
                                supporting = if (sessionState.authEnabled) {
                                    "Nearby browsers must enter this code before viewing your library."
                                } else {
                                    "Receivers can open the session link directly."
                                },
                                actionLabel = if (sessionState.authEnabled) "Regenerate" else null,
                                onAction = if (sessionState.authEnabled) onRegeneratePin else null,
                            )
                            SessionHighlight(
                                title = "Nearby name",
                                value = sessionState.advertisedName ?: "Advertising local session",
                                supporting = sessionState.hostname?.let { "Local hostname: $it" }
                                    ?: "Nearby GhostStream apps can discover this session without showing the raw IP.",
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(onClick = onCopyLink, shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Copy link")
                        }
                        OutlinedButton(onClick = onShareLink, shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Outlined.Share, contentDescription = null)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Share link")
                        }
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
                SessionMetric("Devices", sessionState.connectedClients.size.toString())
                SessionMetric("Speed", formatSpeed(sessionState.transferStats.currentBytesPerSecond))
                SessionMetric("Sent", formatBytes(sessionState.transferStats.totalBytesSent))
                SessionMetric("Network", sessionState.networkAvailability.type.name)
                SessionMetric("Elapsed", formatElapsed(sessionState.transferStats.startedAtEpochMs))
                SessionMetric("Downloads", sessionState.transferStats.completedDownloads.toString())
            }
        }

        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF10141B)),
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = if (sessionState.connectedClients.isEmpty()) "Waiting for devices" else "${sessionState.connectedClients.size} devices connected",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (sessionState.connectedClients.isEmpty()) {
                                    "Nearby browsers will appear here as soon as they open the session."
                                } else {
                                    "Track browsing, playback, and downloads in real time."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (sessionState.connectedClients.size > 1) {
                            OutlinedButton(
                                onClick = onDisconnectAll,
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text("Disconnect all")
                            }
                        }
                    }
                    if (sessionState.connectedClients.isEmpty()) {
                        Text(
                            text = "No devices connected yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        sessionState.connectedClients.forEach { client ->
                            ConnectedClientRow(client = client, onBlockClient = onBlockClient)
                        }
                    }
                }
            }
        }

        if (sessionState.blockedClients.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF120E13)),
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Blocked devices", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        sessionState.blockedClients.forEach { blocked ->
                            BlockedClientRow(blocked = blocked, onUnblockClient = onUnblockClient)
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = onStopSharing,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(Icons.Outlined.StopCircle, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text("Stop sharing")
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun SessionHighlight(
    title: String,
    value: String,
    supporting: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2C)),
            ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(supporting, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (actionLabel != null && onAction != null) {
                OutlinedButton(onClick = onAction, shape = RoundedCornerShape(14.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun SessionMetric(label: String, value: String) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111A28)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ConnectedClientRow(client: ConnectedClient, onBlockClient: (String) -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131E2F)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(client.ipAddress, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = listOfNotNull(client.displayName, client.activity.name.replace('_', ' ')).joinToString(" | "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { onBlockClient(client.ipAddress) }, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Outlined.Block, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text("Block")
            }
        }
    }
}

@Composable
private fun BlockedClientRow(blocked: BlockedClient, onUnblockClient: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(blocked.ipAddress, style = MaterialTheme.typography.titleMedium)
            Text(blocked.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = { onUnblockClient(blocked.ipAddress) }, shape = RoundedCornerShape(14.dp)) {
            Text("Unblock")
        }
    }
}

private fun generateQrBitmap(content: String): Bitmap? {
    return runCatching {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 720, 720)
        Bitmap.createBitmap(bitMatrix.width, bitMatrix.height, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until bitMatrix.width) {
                for (y in 0 until bitMatrix.height) {
                    setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.WHITE else android.graphics.Color.TRANSPARENT)
                }
            }
        }
    }.getOrNull()
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return "${(value * 10).roundToInt() / 10.0} ${units[index]}"
}

private fun formatSpeed(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"

private fun formatElapsed(startedAtEpochMs: Long?): String {
    if (startedAtEpochMs == null || startedAtEpochMs == 0L) return "0s"
    val elapsed = (System.currentTimeMillis() - startedAtEpochMs).coerceAtLeast(0) / 1000
    val hours = elapsed / 3600
    val mins = (elapsed % 3600) / 60
    val secs = elapsed % 60
    return when {
        hours > 0 -> "${hours}h ${mins.toString().padStart(2, '0')}m"
        mins > 0 -> "${mins}m ${secs.toString().padStart(2, '0')}s"
        else -> "${secs}s"
    }
}
