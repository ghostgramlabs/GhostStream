package com.ghoststream.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

@OptIn(ExperimentalLayoutApi::class)
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
    onTogglePreventDownload: (Boolean) -> Unit,
    onToggleRequireUploadApproval: (Boolean) -> Unit,
    onToggleNotifyOnDeviceConnect: (Boolean) -> Unit,
    onToggleNotifyOnFileDownload: (Boolean) -> Unit,
    onToggleNotifyOnUploadRequest: (Boolean) -> Unit,
    onToggleRequireDeviceApproval: (Boolean) -> Unit,
    onAutoStopSelected: (AutoStopOption) -> Unit,
    onPreferredPortChanged: (String) -> Unit,
    onManualPinChanged: (String) -> Unit,
    onBack: () -> Unit,
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
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column {
                Text("Settings", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text("Choose how DirectServe feels and behaves.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            SettingsGroup(title = "General") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text("Theme", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("Choose your preferred appearance", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(ThemeMode.SYSTEM, ThemeMode.DARK, ThemeMode.LIGHT).forEach { mode ->
                            val isSelected = settings.themeMode == mode
                            if (isSelected) {
                                Button(
                                    onClick = { onThemeModeSelected(mode) },
                                    modifier = Modifier.height(40.dp),
                                    colors = ButtonDefaults.buttonColors(),
                                ) {
                                    Text(mode.label(), style = MaterialTheme.typography.labelMedium)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onThemeModeSelected(mode) },
                                    modifier = Modifier.height(40.dp),
                                ) {
                                    Text(mode.label(), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
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
            SettingsGroup(title = "Notifications") {
                SettingsToggleRow("Connection alerts", "Alert when someone connects to your session.", settings.notifyOnDeviceConnect, onToggleNotifyOnDeviceConnect)
                SettingsToggleRow("Download alerts", "Alert when someone downloads a file.", settings.notifyOnFileDownload, onToggleNotifyOnFileDownload)
                SettingsToggleRow("Upload requests", "Alert when someone requests to upload.", settings.notifyOnUploadRequest, onToggleNotifyOnUploadRequest)
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
                SettingsToggleRow("Approve file uploads", "Ask you before browsers can upload files to this device.", settings.requireUploadApproval, onToggleRequireUploadApproval)
                SettingsToggleRow("Approve per device", "Approve once per device, not per upload.", settings.requireDeviceApproval, onToggleRequireDeviceApproval)
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
                SettingsToggleRow("Prevent downloads", "Disable download button in browser access.", settings.preventDownload, onTogglePreventDownload)
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
fun HelpScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text("Help", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = ghostPanelColor()),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("About DirectServe", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("DirectServe lets nearby devices open your shared files in a browser without needing cloud storage or an account.", style = MaterialTheme.typography.bodyLarge)
                    Text("It is platform-independent for receivers, so any device with Wi-Fi or hotspot access and a browser can connect.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No cloud. No account. No internet required.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HelpSectionCard(
                title = "What you can do",
                lines = listOf(
                    "Share videos, photos, music, documents, and folders from your phone.",
                    "Open the session in a browser on Android phones, iPhone, iPad, Windows laptops, Mac, Linux, tablets, desktops, and many smart TVs.",
                    "Any device with Wi-Fi or hotspot access and a browser can connect on the same local network.",
                    "Keep one share open while several devices browse at the same time.",
                ),
            )
            HelpSectionCard(
                title = "Video playback",
                lines = listOf(
                    "Most videos can play directly in the browser.",
                    "Some videos need a temporary browser-ready version for smoother playback on certain devices.",
                    "The original file stays on your phone, and playback preparation does not replace your source file.",
                ),
            )
            HelpSectionCard(
                title = "Receiving files",
                lines = listOf(
                    "Other devices can send files to your phone through the web view when uploads are allowed.",
                    "If upload approval is turned on, you will see a request before the files are accepted.",
                    "Received files are saved on your device and can appear in your history.",
                ),
            )
            HelpSectionCard(
                title = "Multiple devices",
                lines = listOf(
                    "More than one device can connect to the same session.",
                    "The live session screen shows connected devices, activity, and the generated device names with IP addresses.",
                    "You can block devices, disconnect everyone, or protect the session with a PIN.",
                ),
            )
            HelpSectionCard(
                title = "Good to know",
                lines = listOf(
                    "Both devices should be on the same Wi-Fi network or your phone hotspot.",
                    "Public Wi-Fi sometimes blocks local device-to-device traffic, so hotspot is often the best fallback.",
                    "If downloads are disabled in Settings, the web UI hides download actions completely.",
                ),
            )
            HelpSectionCard(
                title = "Quick flow",
                lines = listOf(
                    "1. Add files or a folder.",
                    "2. Start the session.",
                    "3. Open the shown link or scan the QR code on another device.",
                    "4. Browse, play, preview, or upload files depending on what the host allows.",
                ),
            )
        }
    }
}

@Composable
private fun HelpSectionCard(
    title: String,
    lines: List<String>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = ghostPanelColor()),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    ThemeMode.SYSTEM -> "System"
    ThemeMode.DARK -> "Dark"
    ThemeMode.LIGHT -> "Light"
}

@Composable
private fun ghostPanelColor(): androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface
