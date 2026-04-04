package com.ghoststream.feature.settings

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghoststream.core.model.AppSettings
import com.ghoststream.core.model.AutoStopOption
import com.ghoststream.core.model.RecentSession
import com.ghoststream.core.model.ThemeMode

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
    onThemeModeSelected: (ThemeMode) -> Unit,
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
            .background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                Text("Settings", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text("Choose how GhostStream feels and behaves.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            SettingsGroup(title = "General") {
                SettingsChoiceRow(
                    title = "Theme",
                    value = settings.themeMode.label(),
                    onClick = {
                        val next = when (settings.themeMode) {
                            ThemeMode.SYSTEM -> ThemeMode.DARK
                            ThemeMode.DARK -> ThemeMode.LIGHT
                            ThemeMode.LIGHT -> ThemeMode.SYSTEM
                        }
                        onThemeModeSelected(next)
                    },
                )
                SettingsToggleRow("Keep screen awake", "Keep the host screen on during sharing.", settings.keepScreenAwake, onToggleKeepScreenAwake)
                SettingsToggleRow("Vibrate on connect", "Give a small vibration when someone joins.", settings.hapticOnDeviceConnect, onToggleHaptics)
                SettingsToggleRow("Show speed", "Show live transfer speed on the session screen.", settings.showTransferSpeed, onToggleTransferSpeed)
                SettingsToggleRow("Show recent shares", "Keep a short history on this device.", settings.showRecentSessions, onToggleRecentSessions)
                SettingsChoiceRow(
                    title = "Auto-stop",
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
            SettingsGroup(title = "Privacy") {
                SettingsToggleRow("Use PIN", "Ask browsers for a code before opening the session.", settings.requireSessionPin, onToggleRequirePin)
                if (settings.requireSessionPin) {
                    SettingsToggleRow("New PIN each time", "Create a fresh code for every session.", settings.autoGeneratePin, onToggleAutoGeneratePin)
                    if (!settings.autoGeneratePin) {
                        ManualPinRow(currentPin = settings.manualPin, onPinChanged = onManualPinChanged)
                    }
                }
            }
        }

        item {
            SettingsGroup(title = "Browser") {
                SettingsToggleRow("Show thumbnails", "Show quick previews for media.", settings.showThumbnails, onToggleShowThumbnails)
                SettingsToggleRow("Large TV layout", "Make cards and spacing bigger on TVs.", settings.largeTvCards, onToggleLargeTvCards)
                SettingsToggleRow("Highlight downloads", "Keep download buttons easy to spot.", settings.prominentDownloadButton, onToggleProminentDownloads)
            }
        }

        item {
            SettingsGroup(title = "Advanced") {
                SettingsToggleRow("Sign out on stop", "End browser access when sharing stops.", settings.clearAuthOnStop, onToggleClearAuthOnStop)
                SettingsToggleRow("Clear temporary files", "Remove prepared playback files when sharing stops.", settings.ghostMode, onToggleGhostMode)
                ManualPortRow(currentPort = settings.preferredPort.toString(), onPortChanged = onPreferredPortChanged)
            }
        }

        item {
            SettingsGroup(title = "Network") {
                SettingsChoiceRow("Wi-Fi settings", "Put both devices on the same Wi-Fi.", onOpenWifiSettings)
                SettingsChoiceRow("Hotspot settings", "Use your hotspot when Wi-Fi blocks local traffic.", onOpenHotspotSettings)
            }
        }

        item {
            SettingsGroup(title = "Help") {
                SettingsChoiceRow("Privacy promise", "Your files stay on this phone.", onOpenHelp)
                SettingsChoiceRow("How it works", "Nearby devices open the link in a browser.", onOpenHelp)
                Text(
                    text = "Recent shares saved on this device: ${recentSessions.size}",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
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
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = ghostPanelColor()),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("About GhostStream", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("GhostStream lets nearby devices stream or download from your phone in a browser.", style = MaterialTheme.typography.bodyLarge)
                Text("No cloud. No account. No internet required.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Some videos may need a temporary browser-ready copy for smoother playback. The original file stays available to download.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        colors = CardDefaults.cardColors(containerColor = ghostPanelColor()),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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
        label = { Text("PIN") },
        placeholder = { Text("4-6 digits") },
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
        label = { Text("Sharing port") },
        placeholder = { Text("43183") },
        supportingText = {
            Text(
                text = "Most people can leave this alone.",
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

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "Default to system"
    ThemeMode.DARK -> "Dark"
    ThemeMode.LIGHT -> "Light"
}

@Composable
private fun ghostPanelColor(): androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface
