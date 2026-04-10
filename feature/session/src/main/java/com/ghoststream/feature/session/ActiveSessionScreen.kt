package com.ghoststream.feature.session

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ghostgramlabs.directserve.core.resources.R
import com.ghoststream.core.model.BlockedClient
import com.ghoststream.core.model.ConnectedClient
import com.ghoststream.core.model.SessionState
import com.ghoststream.core.model.deviceIdentity
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
    onBack: () -> Unit,
    onTogglePinProtection: (Boolean) -> Unit,
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
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SessionTopBar(onBack = onBack)
        }
        item {
            SessionHeroCard(
                sessionState = sessionState,
                    accessUrl = accessUrl,
                    displayUrl = displayUrl ?: accessUrl ?: stringResource(R.string.session_waiting_local_link),
                    onCopyLink = onCopyLink,
                    onShareLink = onShareLink,
                    onTogglePinProtection = onTogglePinProtection,
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
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = sessionPrimaryButtonColors(),
            ) {
                Icon(Icons.Outlined.StopCircle, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.session_stop_sharing))
            }
        }
    }
}

@Composable
private fun SessionTopBar(
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.session_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
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
    onTogglePinProtection: (Boolean) -> Unit,
    onRegeneratePin: () -> Unit,
) {
    val cardContainerColor = animateColorAsState(
        targetValue = if (sessionState.isSharing) sessionLiveSurface() else MaterialTheme.colorScheme.surface,
        label = "sessionHeroContainer",
    )
    val cardBorderColor = animateColorAsState(
        targetValue = if (sessionState.isSharing) sessionLiveBorder() else MaterialTheme.colorScheme.outline,
        label = "sessionHeroBorder",
    )
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = cardContainerColor.value),
        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor.value),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SessionStatePill(sessionState = sessionState)
                    Spacer(modifier = Modifier.height(14.dp))
                    if (sessionState.isSharing) {
                        LiveFeedbackBanner(
                            title = stringResource(R.string.session_live_now),
                            subtitle = stringResource(R.string.session_live_now_subtitle),
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                    Text(
                        text = if (sessionState.isSharing) stringResource(R.string.session_share_live) else stringResource(R.string.session_share_preparing),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (sessionState.isSharing) {
                            stringResource(R.string.session_share_live_body)
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
                            onTogglePinProtection = onTogglePinProtection,
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
                            onTogglePinProtection = onTogglePinProtection,
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
private fun LiveFeedbackBanner(
    title: String,
    subtitle: String,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = sessionLiveSurface(),
        border = androidx.compose.foundation.BorderStroke(1.dp, sessionLiveBorder()),
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
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                text = stringResource(R.string.session_qr_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.session_qr_body),
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
                        .size(228.dp)
                        .padding(18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    accessUrl?.let { url ->
                        val qrBitmap = remember(url) { generateQrBitmap(url) }
                        qrBitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = stringResource(R.string.session_qr_content_desc),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } ?: Text(
                        text = stringResource(R.string.session_qr_preparing),
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
    onTogglePinProtection: (Boolean) -> Unit,
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
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.session_share_link),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        text = stringResource(R.string.session_share_link_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SessionDetailChip(
                    label = stringResource(R.string.session_network),
                    value = networkLabel(sessionState),
                    showDot = true,
                    modifier = Modifier.widthIn(min = 132.dp),
                )
                SessionDetailChip(
                    label = stringResource(R.string.session_access_pin),
                    value = if (sessionState.authEnabled) (sessionState.pin ?: "----") else stringResource(R.string.session_pin_off),
                    showDot = sessionState.authEnabled,
                    modifier = Modifier.widthIn(min = 132.dp),
                )
                OutlinedButton(
                    onClick = {
                        if (sessionState.authEnabled) {
                            onRegeneratePin()
                        } else {
                            onTogglePinProtection(true)
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = sessionSecondaryButtonColors(),
                ) {
                    Text(if (sessionState.authEnabled) stringResource(R.string.session_new_pin) else stringResource(R.string.session_turn_pin_on))
                }
                if (sessionState.authEnabled) {
                    OutlinedButton(
                        onClick = { onTogglePinProtection(false) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = sessionSecondaryButtonColors(),
                    ) {
                        Text(stringResource(R.string.session_turn_pin_off))
                    }
                }
                sessionState.advertisedName?.takeIf { it.isNotBlank() }?.let { nearby ->
                    SessionDetailChip(
                        label = stringResource(R.string.session_nearby_name),
                        value = nearby,
                        modifier = Modifier.widthIn(min = 170.dp),
                    )
                }
            }

            SessionInfoRow(
                title = stringResource(R.string.session_what_happens_now),
                value = when {
                    !sessionState.isSharing -> stringResource(R.string.session_waiting_ready)
                    sessionState.connectedClients.isEmpty() -> stringResource(R.string.session_waiting_first_device)
                    else -> stringResource(R.string.session_devices_connected_now, sessionState.connectedClients.size)
                },
            )
            sessionState.hostname?.takeIf { it.isNotBlank() }?.let { host ->
                SessionInfoRow(title = stringResource(R.string.session_friendly_local_name), value = host)
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onCopyLink,
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = sessionPrimaryButtonColors(),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.session_copy_link))
                }
                OutlinedButton(
                    onClick = onShareLink,
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = sessionSecondaryButtonColors(),
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.session_share))
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
        SessionMetric(stringResource(R.string.session_metric_devices), sessionState.connectedClients.size.toString())
        SessionMetric(stringResource(R.string.session_metric_sent), formatBytes(sessionState.transferStats.totalBytesSent))
        if (showTransferSpeed) {
            SessionMetric(stringResource(R.string.session_metric_speed), formatSpeed(sessionState.transferStats.currentBytesPerSecond))
        }
        SessionMetric(stringResource(R.string.session_metric_elapsed), formatElapsed(sessionState.transferStats.startedAtEpochMs))
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
                        text = if (sessionState.connectedClients.isEmpty()) stringResource(R.string.session_connected_waiting) else stringResource(R.string.session_connected_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (sessionState.connectedClients.isEmpty()) {
                            stringResource(R.string.session_connected_waiting_body)
                        } else {
                            stringResource(R.string.session_connected_count, sessionState.connectedClients.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (sessionState.connectedClients.size > 1) {
                    OutlinedButton(
                        onClick = onDisconnectAll,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = sessionSecondaryButtonColors(),
                    ) {
                        Text(stringResource(R.string.session_disconnect_all))
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
                    text = stringResource(R.string.session_no_devices),
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
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            val stacked = maxWidth < 520.dp
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConnectedClientSummary(client = client)
                    OutlinedButton(
                        onClick = { onBlockClient(client.ipAddress) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = sessionSecondaryButtonColors(),
                    ) {
                        Icon(Icons.Outlined.Block, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.session_block_device))
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    ConnectedClientSummary(client = client, modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = { onBlockClient(client.ipAddress) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = sessionSecondaryButtonColors(),
                    ) {
                        Icon(Icons.Outlined.Block, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.session_block))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedClientSummary(
    client: ConnectedClient,
    modifier: Modifier = Modifier,
) {
    val identity = deviceIdentity(client.ipAddress)
    Column(modifier = modifier) {
        Text(
            text = identity.generatedName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = listOfNotNull(
                identity.ipAddress,
                client.displayName?.takeIf { it.isNotBlank() },
                client.activity.name.replace('_', ' '),
            )
                .distinct()
                .joinToString(" | "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                text = stringResource(R.string.session_blocked_title),
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
    val identity = deviceIdentity(blocked.ipAddress)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            val stacked = maxWidth < 520.dp
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column {
                        Text(identity.generatedName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            listOf(identity.ipAddress, blocked.note).joinToString(" | "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = { onUnblockClient(blocked.ipAddress) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = sessionSecondaryButtonColors(),
                    ) {
                        Text(stringResource(R.string.session_unblock_device))
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(identity.generatedName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            listOf(identity.ipAddress, blocked.note).joinToString(" | "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = { onUnblockClient(blocked.ipAddress) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = sessionSecondaryButtonColors(),
                    ) {
                        Text(stringResource(R.string.session_unblock))
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionStatePill(sessionState: SessionState) {
    val label = when {
        sessionState.isSharing -> stringResource(R.string.session_state_sharing_now)
        sessionState.networkAvailability.isReady -> stringResource(R.string.session_state_preparing)
        else -> stringResource(R.string.session_state_network_needed)
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (sessionState.isSharing || sessionState.networkAvailability.isReady) sessionAccentSurface() else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (sessionState.isSharing || sessionState.networkAvailability.isReady) sessionAccentBorder() else MaterialTheme.colorScheme.outline,
        ),
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
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = if (showDot) sessionAccentSurface() else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (showDot) sessionAccentBorder() else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showDot) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp)),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
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

@Composable
private fun networkLabel(sessionState: SessionState): String {
    return when {
        sessionState.networkAvailability.type.name == "HOTSPOT" -> stringResource(R.string.session_network_hotspot)
        sessionState.networkAvailability.type.name == "WIFI" -> stringResource(R.string.session_network_wifi)
        sessionState.networkAvailability.type.name == "LOCAL" -> stringResource(R.string.session_network_local)
        else -> stringResource(R.string.session_network_offline)
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

@Composable
private fun sessionPrimaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f),
)

@Composable
private fun sessionSecondaryButtonColors() = ButtonDefaults.outlinedButtonColors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f),
)

@Composable
private fun sessionAccentSurface() = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)

@Composable
private fun sessionAccentBorder() = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)

@Composable
private fun sessionLiveSurface() = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)

@Composable
private fun sessionLiveBorder() = MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
