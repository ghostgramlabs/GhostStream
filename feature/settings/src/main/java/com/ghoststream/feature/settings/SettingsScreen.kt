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
    onPreferredPortChanged: (String) -> Unit,
    onManualPinChanged: (String) -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenHotspotSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    showDebugTools: Boolean = false,
    debugLogLocation: String = "",
    onShareDebugLog: () -> Unit = {},
    onClearDebugLog: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF04070A), Color(0xFF0F1724), Color(0xFF05080C)),
                ),
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                Text("Settings", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text("Control privacy, playback, and local session behavior.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            SettingsGroup(title = "Sharing") {
                SettingsToggleRow("Keep screen awake", "Prevents the host screen from locking while you monitor a live session.", settings.keepScreenAwake, onToggleKeepScreenAwake)
                SettingsToggleRow("Haptic on connect", "Vibrate when a nearby browser connects for the first time.", settings.hapticOnDeviceConnect, onToggleHaptics)
                SettingsToggleRow("Show transfer speed", "Display live throughput on the sharing screen.", settings.showTransferSpeed, onToggleTransferSpeed)
                SettingsToggleRow("Show recent sessions", "Keep a lightweight local history of finished shares.", settings.showRecentSessions, onToggleRecentSessions)
                ManualPortRow(currentPort = settings.preferredPort.toString(), onPortChanged = onPreferredPortChanged)
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
                SettingsToggleRow("Require session PIN", "Browsers must enter a session code before opening your library.", settings.requireSessionPin, onToggleRequirePin)
                if (settings.requireSessionPin) {
                    SettingsToggleRow("Auto-generate PIN", "Create a new random PIN for each share session.", settings.autoGeneratePin, onToggleAutoGeneratePin)
                    if (!settings.autoGeneratePin) {
                        ManualPinRow(currentPin = settings.manualPin, onPinChanged = onManualPinChanged)
                    }
                }
                SettingsToggleRow("Clear auth on stop", "Expire every browser session as soon as sharing ends.", settings.clearAuthOnStop, onToggleClearAuthOnStop)
                SettingsToggleRow("Ghost Mode", "Wipe temporary playback assets, auth tokens, and session traces on stop.", settings.ghostMode, onToggleGhostMode)
            }
        }

        item {
            SettingsGroup(title = "Browser UI") {
                SettingsToggleRow("Force dark theme", "Keep the receiver browser experience in the GhostStream dark look.", settings.forceDarkBrowserTheme, onToggleDarkBrowserTheme)
                SettingsToggleRow("Show thumbnails", "Generate quick visual previews for photos and videos.", settings.showThumbnails, onToggleShowThumbnails)
                SettingsToggleRow("Larger TV-friendly cards", "Use bigger spacing and cards for TV browsers and distance viewing.", settings.largeTvCards, onToggleLargeTvCards)
                SettingsToggleRow("Prominent download buttons", "Keep file download actions visible alongside playback actions.", settings.prominentDownloadButton, onToggleProminentDownloads)
            }
        }

        item {
            SettingsGroup(title = "Network Help") {
                SettingsChoiceRow("Open Wi-Fi settings", "Connect nearby devices to the same Wi-Fi network.", onOpenWifiSettings)
                SettingsChoiceRow("Open hotspot settings", "Use your phone hotspot when public Wi-Fi blocks device-to-device traffic.", onOpenHotspotSettings)
            }
        }

        item {
            SettingsGroup(title = "About") {
                SettingsChoiceRow("Privacy promise", "Data stays on your device. No cloud relay, no account system.", onOpenHelp)
                SettingsChoiceRow("How GhostStream works", "Nearby devices open a browser and stream over your local network.", onOpenHelp)
                Text(
                    text = "Recent sessions tracked locally: ${recentSessions.size}",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (showDebugTools) {
            item {
                SettingsGroup(title = "Debug") {
                    SettingsChoiceRow("Share debug log", "Email the latest startup and session log.", onShareDebugLog)
                    SettingsChoiceRow("Clear debug log", debugLogLocation, onClearDebugLog)
                }
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
                Text("It does not create cloud accounts, remote internet links, or screen mirroring sessions.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Some formats may need compatibility preparation before smooth browser playback. The original file download stays available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        placeholder = { Text("Enter 4-6 digit PIN") },
        shape = RoundedCornerShape(14.dp),
        singleLine = true,
    )
}

@Composable
private fun ManualPortRow(currentPort: String, onPortChanged: (String) -> Unit) {
    OutlinedTextField(
        value = currentPort,
        onValueChange = { newValue ->
            val cleaned = newValue.filter(Char::isDigit).take(5)
            onPortChanged(cleaned)
        },
        modifier = Modifier
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .fillMaxWidth(),
        label = { Text("Session port") },
        placeholder = { Text("43183") },
        supportingText = {
            Text(
                text = "GhostStream will try to reuse this local port for each sharing session.",
                style = MaterialTheme.typography.bodySmall,
            )
        },
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
