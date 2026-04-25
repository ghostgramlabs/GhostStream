package com.ghoststream.feature.history

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghostgramlabs.directserve.core.resources.R
import com.ghostgramlabs.directserve.core.resources.R as SharedR
import com.ghostgramlabs.directserve.core.resources.ui.GhostSpacing
import com.ghoststream.core.model.QuickTextDevice
import com.ghoststream.core.model.QuickTextMessage
import com.ghoststream.core.model.QuickTextTargetType

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickTextScreen(
    messages: List<QuickTextMessage>,
    devices: List<QuickTextDevice>,
    onBack: () -> Unit,
    onSend: (text: String, targetType: QuickTextTargetType, targetId: String?, targetName: String?) -> Unit,
    onCopyMessage: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val targetDevices = remember(devices) { devices.filterNot { it.isHostPhone } }
    val broadcastLabel = stringResource(R.string.quick_text_broadcast)
    var selectedTargetId by rememberSaveable(targetDevices) {
        mutableStateOf(targetDevices.firstOrNull()?.id ?: BROADCAST_ID)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = GhostSpacing.screenVertical),
        verticalArrangement = Arrangement.spacedBy(GhostSpacing.section),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GhostSpacing.screenHorizontal),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
                Text(
                    text = stringResource(R.string.quick_text_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = GhostSpacing.screenHorizontal)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    modifier = Modifier.padding(GhostSpacing.card),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.quick_text_send_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        placeholder = { Text(stringResource(R.string.quick_text_placeholder)) },
                    )
                    Text(
                        text = stringResource(R.string.quick_text_target_label),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        targetDevices.forEach { device ->
                            TargetChip(
                                label = device.name,
                                selected = selectedTargetId == device.id,
                                onClick = { selectedTargetId = device.id },
                            )
                        }
                        TargetChip(
                            label = broadcastLabel,
                            selected = selectedTargetId == BROADCAST_ID,
                            onClick = { selectedTargetId = BROADCAST_ID },
                        )
                    }
                    Button(
                        onClick = {
                            val isBroadcast = selectedTargetId == BROADCAST_ID
                            val target = targetDevices.firstOrNull { it.id == selectedTargetId }
                            onSend(
                                draft,
                                if (isBroadcast) QuickTextTargetType.BROADCAST else QuickTextTargetType.DEVICE,
                                if (isBroadcast) null else target?.id,
                                if (isBroadcast) broadcastLabel else target?.name,
                            )
                            draft = ""
                        },
                        enabled = draft.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.quick_text_send))
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GhostSpacing.screenHorizontal),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.quick_text_history_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.quick_text_history_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (messages.isNotEmpty()) {
                    OutlinedButton(onClick = onClearAll) {
                        Text(stringResource(R.string.quick_text_clear_all))
                    }
                }
            }
        }

        if (messages.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.quick_text_empty),
                    modifier = Modifier.padding(horizontal = GhostSpacing.screenHorizontal),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(messages, key = { it.id }) { message ->
                QuickTextMessageCard(
                    message = message,
                    onCopy = { onCopyMessage(message.text) },
                    onOpenLink = { onOpenLink(message.text) },
                    onDelete = { onDeleteMessage(message.id) },
                )
            }
        }
    }
}

@Composable
private fun QuickTextMessageCard(
    message: QuickTextMessage,
    onCopy: () -> Unit,
    onOpenLink: () -> Unit,
    onDelete: () -> Unit,
) {
    val targetLabel = if (message.targetType == QuickTextTargetType.BROADCAST) {
        stringResource(R.string.quick_text_broadcast)
    } else {
        message.targetName.orEmpty()
    }
    Card(
        modifier = Modifier
            .padding(horizontal = GhostSpacing.screenHorizontal)
            .fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(GhostSpacing.card),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(message.text, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = listOf(message.senderName, targetLabel, DateFormat.format("MMM d, h:mm a", message.timestampMs).toString())
                    .joinToString(stringResource(SharedR.string.common_separator_pipe)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCopy) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.quick_text_copy))
                }
                if (message.isLink) {
                    OutlinedButton(onClick = onOpenLink) {
                        Icon(Icons.Outlined.Link, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.quick_text_open_link))
                    }
                }
                OutlinedButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.common_delete))
                }
            }
        }
    }
}

@Composable
private fun TargetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private const val BROADCAST_ID = "__broadcast__"
