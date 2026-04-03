package com.ghoststream.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghoststream.core.model.AppSettings
import com.ghoststream.core.model.AutoStopOption
import com.ghoststream.core.model.RecentSession

@Composable
fun SettingsScreen(
    settings: AppSettings,
    recentSessions: List<RecentSession>,
    onToggleKeepScreenAwake: (Boolean) -> Unit,
    onToggleHaptics: (Boolean) -> Unit,
    onToggleTransferSpeed: (Boolean) -> Unit,
    onToggleRecentSessions: (Boolean) -> Unit,
    onToggleRequirePin: (Boolean) -> Unit,
    onToggleAutoGeneratePin: (Boolean) -> Unit,
    onToggleClearAuthOnStop: (Boolean) -> Unit,
    onToggleGhostMode: (Boolean) -> Unit,
    onToggleDarkBrowserTheme: (Boolean) -> Unit,
    onToggleShowThumbnails: (Boolean) -> Unit,
    onToggleLargeTvCards: (Boolean) -> Unit,
    onToggleProminentDownloads: (Boolean) -> Unit,
    onAutoStopSelected: (AutoStopOption) -> Unit,
    onManualPinChanged: (String) -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenHotspotSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF04070A), Color(0xFF101928), Color(0xFF05080C)),
                ),
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                Text("Settings", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text("Privacy-first controls", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            SettingsGroup(title = "Sharing") {
                SettingsToggleRow("Keep screen awake", "Prevents screen from locking while session screen is open", settings.keepScreenAwake, onToggleKeepScreenAwake)
                SettingsToggleRow("Haptic on connect", "Vibrate when a new device connects", settings.hapticOnDeviceConnect, onToggleHaptics)
                SettingsToggleRow("Show transfer speed", "Display MB/s on the active session screen", settings.showTransferSpeed, onToggleTransferSpeed)
                SettingsToggleRow("Show recent sessions", "Track session history locally", settings.showRecentSessions, onToggleRecentSessions)
                SettingsChoiceRow(
                    title = "Auto-stop after inactivity",
                    value = when (settings.autoStop) {
                        AutoStopOption.NEVER -> "Never"
                        AutoStopOption.MINUTES_15 -> "15 min"
                        AutoStopOption.MINUTES_30 -> "30 min"
                        AutoStopOption.HOUR_1 -> "1 hour"
                    },
                    onClick = {
                        val next = when (settings.autoStop) {
                            AutoStopOption.NEVER -> AutoStopOption.MINUTES_15
                            AutoStopOption.MINUTES_15 -> AutoStopOption.MINUTES_30
                            AutoStopOption.MINUTES_30 -> AutoStopOption.HOUR_1
                            AutoStopOption.HOUR_1 -> AutoStopOption.NEVER
                        }
                        onAutoStopSelected(next)
                    },
                )
            }
        }

        item {
            SettingsGroup(title = "Security") {
                SettingsToggleRow("Require session PIN", "Browsers must enter a PIN to access your files", settings.requireSessionPin, onToggleRequirePin)
                if (settings.requireSessionPin) {
                    SettingsToggleRow("Auto-generate PIN", "Create a random 4-digit PIN each session", settings.autoGeneratePin, onToggleAutoGeneratePin)
                    if (!settings.autoGeneratePin) {
                        ManualPinRow(currentPin = settings.manualPin, onPinChanged = onManualPinChanged)
                    }
                }
                SettingsToggleRow("Clear auth on stop", "Expire all browser sessions when sharing stops", settings.clearAuthOnStop, onToggleClearAuthOnStop)
                SettingsToggleRow("Ghost Mode", "Auto-wipe temp files, cached streams, and auth on stop", settings.ghostMode, onToggleGhostMode)
            }
        }

        item {
            SettingsGroup(title = "Browser UI") {
                SettingsToggleRow("Force dark theme", "Always use dark mode in the browser", settings.forceDarkBrowserTheme, onToggleDarkBrowserTheme)
                SettingsToggleRow("Show thumbnails", "Generate and display preview images", settings.showThumbnails, onToggleShowThumbnails)
                SettingsToggleRow("Larger TV-friendly cards", "Bigger cards for smart TV browsers", settings.largeTvCards, onToggleLargeTvCards)
                SettingsToggleRow("Prominent download buttons", "Show download button on every card", settings.prominentDownloadButton, onToggleProminentDownloads)
            }
        }

        item {
            SettingsGroup(title = "Network Help") {
                SettingsChoiceRow("Open Wi-Fi settings", "Connect nearby devices to the same network", onOpenWifiSettings)
                SettingsChoiceRow("Open hotspot settings", "Best for travel and direct sharing", onOpenHotspotSettings)
            }
        }

        item {
            SettingsGroup(title = "About") {
                SettingsChoiceRow("Privacy promise", "Data stays on your device", onOpenHelp)
                SettingsChoiceRow("How GhostStream works", "Local-only browser access", onOpenHelp)
                Text(
                    text = "Recent sessions: ${recentSessions.size}",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Spacer(modifier = Modifier.height(18.dp)) }
    }
}

@Composable
fun HelpScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF04070A), Color(0xFF121B27))),
            )
            .padding(24.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF101826)),
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("About GhostStream", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("GhostStream turns your phone into a private local media server for nearby devices.", style = MaterialTheme.typography.bodyLarge)
                Text("It does not create cloud accounts, remote internet links, or screen-mirroring sessions.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Some formats may need optimization later for smooth browser playback. Every file can still be downloaded in its original form.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Nearby and offline only. The receiver only needs a browser.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1522)),
    ) {
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ManualPinRow(currentPin: String, onPinChanged: (String) -> Unit) {
    OutlinedTextField(
        value = currentPin,
        onValueChange = { newValue ->
            val cleaned = newValue.filter(Char::isDigit).take(6)
            onPinChanged(cleaned)
        },
        modifier = Modifier
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .fillMaxWidth(),
        label = { Text("Custom PIN") },
        placeholder = { Text("Enter 4–6 digit PIN") },
        shape = RoundedCornerShape(14.dp),
        singleLine = true,
    )
}

@Composable
private fun SettingsChoiceRow(title: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(title)
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}
