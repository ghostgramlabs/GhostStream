package com.ghoststream.feature.session

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.roundToInt

@Composable
fun ActiveSessionScreen(
    sessionState: SessionState,
    hapticOnDeviceConnect: Boolean,
    onCopyLink: () -> Unit,
    onShareLink: () -> Unit,
    onStopSharing: () -> Unit,
    onBlockClient: (String) -> Unit,
    onUnblockClient: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val accessUrl = remember(sessionState.sessionUrl, sessionState.networkAvailability.localAddress, sessionState.serverPort) {
        sessionState.sessionUrl
            ?.takeIf { it.startsWith("http://") && !it.startsWith("http://:") }
            ?: sessionState.networkAvailability.localAddress?.let { address ->
            sessionState.serverPort?.let { port -> "http://$address:$port" }
        }
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
                    listOf(Color(0xFF030507), Color(0xFF0B1624), Color(0xFF04070B)),
                ),
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 20.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1725)),
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (sessionState.isSharing) "Sharing is live" else "Preparing browser access...",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    accessUrl?.let { url ->
                        val qrBitmap = remember(url) { generateQrBitmap(url) }
                        qrBitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Session QR",
                                modifier = Modifier
                                    .size(220.dp)
                                    .padding(top = 8.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(url, style = MaterialTheme.typography.bodyLarge)
                        sessionState.hostname?.let { hostname ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Local name: $hostname",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } ?: run {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = sessionState.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        sessionState.networkAvailability.localAddress?.let { address ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Local IP: $address",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onCopyLink, shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Copy")
                        }
                        OutlinedButton(onClick = onShareLink, shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Outlined.Share, contentDescription = null)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Share")
                        }
                    }
                    if (sessionState.authEnabled) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Session PIN: ${sessionState.pin ?: "----"}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SessionMetric("Devices", sessionState.connectedClients.size.toString(), Modifier.weight(1f))
                SessionMetric("Speed", formatSpeed(sessionState.transferStats.currentBytesPerSecond), Modifier.weight(1f))
                SessionMetric("Sent", formatBytes(sessionState.transferStats.totalBytesSent), Modifier.weight(1f))
            }
        }

        item {
            Row(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SessionMetric("Network", sessionState.networkAvailability.type.name, Modifier.weight(1f))
                SessionMetric("Streams", sessionState.transferStats.activeStreamCount.toString(), Modifier.weight(1f))
                SessionMetric("Downloads", sessionState.transferStats.completedDownloads.toString(), Modifier.weight(1f))
            }
        }

        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101A)),
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = if (sessionState.connectedClients.isEmpty()) "Waiting for devices..." else "${sessionState.connectedClients.size} devices connected",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (sessionState.connectedClients.isEmpty()) {
                        Text(
                            text = "Nearby browsers will appear here when they open the session.",
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
                Text("Stop Sharing")
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun SessionMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
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
