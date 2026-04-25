package com.ghoststream.feature.session

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghostgramlabs.directserve.core.resources.R
import com.ghostgramlabs.directserve.core.resources.ui.GhostSpacing
import com.ghoststream.core.model.LiveAudioStatus
import com.ghoststream.core.model.LiveScreenSessionState
import com.ghoststream.core.model.LiveScreenStatus
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun LiveScreenControlScreen(
    state: LiveScreenSessionState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopyLink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val qrBitmap = remember(state.displayUrl) {
        state.displayUrl?.let(::generateQrBitmap)
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
                    text = stringResource(R.string.live_screen_title),
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
                        text = when (state.status) {
                            LiveScreenStatus.LIVE -> stringResource(R.string.live_screen_status_live)
                            LiveScreenStatus.STARTING -> stringResource(R.string.live_screen_status_starting)
                            LiveScreenStatus.ERROR -> stringResource(R.string.live_screen_status_stopped)
                            LiveScreenStatus.STOPPED -> stringResource(R.string.live_screen_status_stopped)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.live_screen_intro),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.live_screen_audio_limit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (state.audioStatus) {
                        LiveAudioStatus.AUDIO_LIVE -> Text(
                            text = stringResource(R.string.live_screen_audio_available),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        LiveAudioStatus.AUDIO_SUPPORTED,
                        LiveAudioStatus.AUDIO_INITIALIZING,
                        -> Unit
                        LiveAudioStatus.AUDIO_SILENT,
                        LiveAudioStatus.AUDIO_FAILED,
                        -> Text(
                            text = stringResource(R.string.live_screen_audio_blocked_or_silent),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LiveAudioStatus.AUDIO_UNAVAILABLE -> Text(
                            text = stringResource(R.string.live_screen_audio_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.pin?.takeIf { it.isNotBlank() }?.let { pin ->
                        Text(
                            text = stringResource(R.string.live_screen_pin_label, pin),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.live_screen_pin_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.displayUrl?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.live_screen_qr_content_desc),
                            modifier = Modifier
                                .size(220.dp)
                                .align(Alignment.CenterHorizontally),
                        )
                    }
                    if (state.width != null && state.height != null) {
                        val width = state.width ?: 0
                        val height = state.height ?: 0
                        Text(
                            text = stringResource(
                                R.string.live_screen_stats,
                                width,
                                height,
                                state.fps,
                                state.bitrateKbps,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.lastError?.takeIf { it.isNotBlank() }?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!state.isActive) {
                        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.live_screen_start))
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = onCopyLink, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.session_share_link))
                            }
                            Button(onClick = onStop, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.StopCircle, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.live_screen_stop))
                            }
                        }
                    }
                }
            }
        }
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
                        if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
                    )
                }
            }
        }
    }.getOrNull()
}
