package com.ghoststream.feature.session

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StopCircle
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    showTransferSpeed: Boolean,
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
            .background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SessionHeroCard(
                sessionState = sessionState,
                accessUrl = accessUrl,
                displayUrl = displayUrl ?: accessUrl ?: "Waiting for local link",
                onCopyLink = onCopyLink,
                onShareLink = onShareLink,
                onRegeneratePin = onRegeneratePin,
            )
        }

        item {
            SessionStatsRow(
                sessionState = sessionState,
                showTransferSpeed = showTransferSpeed,
            )
        }

        item {
            ConnectedDevicesCard(
                sessionState = sessionState,
                onBlockClient = onBlockClient,
                onDisconnectAll = onDisconnectAll,
            )
        }

        if (sessionState.blockedClients.isNotEmpty()) {
            item {
                BlockedDevicesCard(
                    blockedClients = sessionState.blockedClients,
                    onUnblockClient = onUnblockClient,
                )
            }
        }

        item {
            Button(
                onClick = onStopSharing,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Outlined.StopCircle, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Stop sharing")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionHeroCard(
    sessionState: SessionState,
    accessUrl: String?,
    displayUrl: String,
    onCopyLink: () -> Unit,
    onShareLink: () -> Unit,
    onRegeneratePin: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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
                    SessionStatePill(sessionState = sessionState)
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = if (sessionState.isSharing) "Your share is live" else "Preparing your share",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (sessionState.isSharing) {
                            "People nearby can scan this QR code or open the link below in any browser."
                        } else {
                            sessionState.message
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            BoxWithConstraints {
                val stacked = maxWidth < 700.dp
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SessionQrCard(accessUrl = accessUrl)
                        SessionAccessPanel(
                            sessionState = sessionState,
                            displayUrl = displayUrl,
                            onCopyLink = onCopyLink,
                            onShareLink = onShareLink,
                            onRegeneratePin = onRegeneratePin,
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SessionQrCard(
                            accessUrl = accessUrl,
                            modifier = Modifier.weight(0.95f),
                        )
                        SessionAccessPanel(
                            sessionState = sessionState,
                            displayUrl = displayUrl,
                            onCopyLink = onCopyLink,
                            onShareLink = onShareLink,
                            onRegeneratePin = onRegeneratePin,
                            modifier = Modifier.weight(1.05f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionQrCard(
    accessUrl: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Scan to open in a browser",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Best for phones, tablets, laptops, and TVs on the same Wi-Fi or hotspot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Box(
                    modifier = Modifier
                        .size(248.dp)
                        .padding(18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    accessUrl?.let { url ->
                        val qrBitmap = remember(url) { generateQrBitmap(url) }
                        qrBitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Session QR",
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } ?: Text(
                        text = "Preparing QR",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Black.copy(alpha = 0.72f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionAccessPanel(
    sessionState: SessionState,
    displayUrl: String,
    onCopyLink: () -> Unit,
    onShareLink: () -> Unit,
    onRegeneratePin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Share link",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = displayUrl,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Open this on the same Wi-Fi or hotspot. If typing is hard, scan the QR code instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SessionDetailChip(label = "Network", value = networkLabel(sessionState), showDot = true)
                SessionDetailChip(
                    label = "Access PIN",
                    value = if (sessionState.authEnabled) (sessionState.pin ?: "----") else "Off",
                    showDot = sessionState.authEnabled,
                )
                sessionState.advertisedName?.takeIf { it.isNotBlank() }?.let { nearby ->
                    SessionDetailChip(label = "GhostStream app", value = nearby)
                }
            }

            SessionInfoRow(
                title = "What happens now",
                value = when {
                    !sessionState.isSharing -> "GhostStream is getting the session ready."
                    sessionState.connectedClients.isEmpty() -> "Waiting for the first device to open the link or scan the QR code."
                    else -> "${sessionState.connectedClients.size} device${if (sessionState.connectedClients.size == 1) "" else "s"} connected right now."
                },
            )
            sessionState.hostname?.takeIf { it.isNotBlank() }?.let { host ->
                SessionInfoRow(title = "Friendly local name", value = host)
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onCopyLink,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy link")
                }
                OutlinedButton(
                    onClick = onShareLink,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share")
                }
                if (sessionState.authEnabled) {
                    OutlinedButton(
                        onClick = onRegeneratePin,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    ) {
                        Text("New PIN")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionStatsRow(
    sessionState: SessionState,
    showTransferSpeed: Boolean,
) {
    FlowRow(
        modifier = Modifier.padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SessionMetric("Devices", sessionState.connectedClients.size.toString())
        SessionMetric("Sent", formatBytes(sessionState.transferStats.totalBytesSent))
        if (showTransferSpeed) {
            SessionMetric("Speed", formatSpeed(sessionState.transferStats.currentBytesPerSecond))
        }
        SessionMetric("Elapsed", formatElapsed(sessionState.transferStats.startedAtEpochMs))
    }
}

@Composable
private fun SessionMetric(
    label: String,
    value: String,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier
                .width(108.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ConnectedDevicesCard(
    sessionState: SessionState,
    onBlockClient: (String) -> Unit,
    onDisconnectAll: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (sessionState.connectedClients.isEmpty()) "Waiting for devices" else "Connected devices",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (sessionState.connectedClients.isEmpty()) {
                            "They will appear here when someone opens your session."
                        } else {
                            "${sessionState.connectedClients.size} device${if (sessionState.connectedClients.size == 1) "" else "s"} active right now."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (sessionState.connectedClients.size > 1) {
                    OutlinedButton(
                        onClick = onDisconnectAll,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    ) {
                        Text("Disconnect all")
                    }
                }
            }

            if (sessionState.connectedClients.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Text(
                    text = "No devices connected yet. The first device will appear here after it opens the link or scans the QR code.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                sessionState.connectedClients.forEach { client ->
                    ConnectedClientRow(client = client, onBlockClient = onBlockClient)
                }
            }
        }
    }
}

@Composable
private fun ConnectedClientRow(
    client: ConnectedClient,
    onBlockClient: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = client.ipAddress,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = listOfNotNull(client.displayName, client.activity.name.replace('_', ' ')).joinToString(" | "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(
                onClick = { onBlockClient(client.ipAddress) },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                Icon(Icons.Outlined.Block, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Block")
            }
        }
    }
}

@Composable
private fun BlockedDevicesCard(
    blockedClients: List<BlockedClient>,
    onUnblockClient: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Blocked devices",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            blockedClients.forEach { blocked ->
                BlockedClientRow(blocked = blocked, onUnblockClient = onUnblockClient)
            }
        }
    }
}

@Composable
private fun BlockedClientRow(
    blocked: BlockedClient,
    onUnblockClient: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(blocked.ipAddress, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(4.dp))
                Text(blocked.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(
                onClick = { onUnblockClient(blocked.ipAddress) },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                Text("Unblock")
            }
        }
    }
}

@Composable
private fun SessionStatePill(sessionState: SessionState) {
    val label = when {
        sessionState.isSharing -> "Sharing now"
        sessionState.networkAvailability.isReady -> "Preparing"
        else -> "Network needed"
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (sessionState.isSharing || sessionState.networkAvailability.isReady) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                        shape = RoundedCornerShape(999.dp),
                    ),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SessionDetailChip(
    label: String,
    value: String,
    showDot: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showDot) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp)),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = "$label ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SessionInfoRow(
    title: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun generateQrBitmap(content: String): Bitmap? {
    return runCatching {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 720, 720)
        Bitmap.createBitmap(bitMatrix.width, bitMatrix.height, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until bitMatrix.width) {
                for (y in 0 until bitMatrix.height) {
                    setPixel(
                        x,
                        y,
                        if (bitMatrix[x, y]) android.graphics.Color.parseColor("#0F172A") else android.graphics.Color.WHITE,
                    )
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

private fun networkLabel(sessionState: SessionState): String {
    return when {
        sessionState.networkAvailability.type.name == "HOTSPOT" -> "Hotspot"
        sessionState.networkAvailability.type.name == "WIFI" -> "Wi-Fi"
        sessionState.networkAvailability.type.name == "LOCAL" -> "Local"
        else -> "Offline"
    }
}

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
