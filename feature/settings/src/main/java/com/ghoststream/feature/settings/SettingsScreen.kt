package com.ghoststream.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghostgramlabs.directserve.core.resources.R
import com.ghostgramlabs.directserve.core.resources.ui.GhostSpacing
import com.ghoststream.core.model.AppSettings
import com.ghoststream.core.model.AutoStopOption
import com.ghoststream.core.model.RecentSession
import com.ghoststream.core.model.ThemeMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    recentSessions: List<RecentSession>,
    currentLanguageLabel: String,
    appVersionLabel: String,
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
    onOpenLanguage: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenHotspotSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onViewOnboarding: () -> Unit,
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
        contentPadding = PaddingValues(vertical = GhostSpacing.screenVertical),
        verticalArrangement = Arrangement.spacedBy(GhostSpacing.section),
    ) {
        item {
            Row(
                modifier = Modifier.padding(
                    horizontal = GhostSpacing.screenHorizontal,
                    vertical = GhostSpacing.headerVertical,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column {
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.settings_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            SettingsGroup(title = stringResource(R.string.settings_group_general)) {
                SettingsChoiceRow(stringResource(R.string.settings_language), currentLanguageLabel, onOpenLanguage)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = GhostSpacing.listItem, vertical = 12.dp),
                ) {
                    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.settings_theme_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    colors = ButtonDefaults.buttonColors(),
                                ) {
                                    Text(mode.label(), style = MaterialTheme.typography.labelMedium)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onThemeModeSelected(mode) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text(mode.label(), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                SettingsToggleRow(stringResource(R.string.settings_keep_screen_awake), stringResource(R.string.settings_keep_screen_awake_desc), settings.keepScreenAwake, onToggleKeepScreenAwake)
                SettingsToggleRow(stringResource(R.string.settings_vibrate_on_connect), stringResource(R.string.settings_vibrate_on_connect_desc), settings.hapticOnDeviceConnect, onToggleHaptics)
                SettingsToggleRow(stringResource(R.string.settings_show_speed), stringResource(R.string.settings_show_speed_desc), settings.showTransferSpeed, onToggleTransferSpeed)
                SettingsToggleRow(stringResource(R.string.settings_show_recent_shares), stringResource(R.string.settings_show_recent_shares_desc), settings.showRecentSessions, onToggleRecentSessions)
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_auto_stop),
                    value = when (settings.autoStop) {
                        AutoStopOption.NEVER -> stringResource(R.string.settings_auto_stop_never)
                        AutoStopOption.MINUTES_15 -> stringResource(R.string.settings_auto_stop_15m)
                        AutoStopOption.MINUTES_30 -> stringResource(R.string.settings_auto_stop_30m)
                        AutoStopOption.HOUR_1 -> stringResource(R.string.settings_auto_stop_1h)
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
            SettingsGroup(title = stringResource(R.string.settings_group_notifications)) {
                SettingsToggleRow(stringResource(R.string.settings_connection_alerts), stringResource(R.string.settings_connection_alerts_desc), settings.notifyOnDeviceConnect, onToggleNotifyOnDeviceConnect)
                SettingsToggleRow(stringResource(R.string.settings_download_alerts), stringResource(R.string.settings_download_alerts_desc), settings.notifyOnFileDownload, onToggleNotifyOnFileDownload)
                SettingsToggleRow(stringResource(R.string.settings_upload_requests), stringResource(R.string.settings_upload_requests_desc), settings.notifyOnUploadRequest, onToggleNotifyOnUploadRequest)
            }
        }

        item {
            SettingsGroup(title = stringResource(R.string.settings_group_privacy)) {
                SettingsToggleRow(stringResource(R.string.settings_use_pin), stringResource(R.string.settings_use_pin_desc), settings.requireSessionPin, onToggleRequirePin)
                if (settings.requireSessionPin) {
                    SettingsToggleRow(stringResource(R.string.settings_new_pin_each_time), stringResource(R.string.settings_new_pin_each_time_desc), settings.autoGeneratePin, onToggleAutoGeneratePin)
                    if (!settings.autoGeneratePin) {
                        ManualPinRow(currentPin = settings.manualPin, onPinChanged = onManualPinChanged)
                    }
                }
                SettingsToggleRow(stringResource(R.string.settings_approve_file_uploads), stringResource(R.string.settings_approve_file_uploads_desc), settings.requireUploadApproval, onToggleRequireUploadApproval)
                SettingsToggleRow(stringResource(R.string.settings_approve_per_device), stringResource(R.string.settings_approve_per_device_desc), settings.requireDeviceApproval, onToggleRequireDeviceApproval)
            }
        }

        item {
            SettingsGroup(title = stringResource(R.string.settings_group_browser)) {
                SettingsToggleRow(stringResource(R.string.settings_show_thumbnails), stringResource(R.string.settings_show_thumbnails_desc), settings.showThumbnails, onToggleShowThumbnails)
                SettingsToggleRow(stringResource(R.string.settings_large_tv_layout), stringResource(R.string.settings_large_tv_layout_desc), settings.largeTvCards, onToggleLargeTvCards)
                SettingsToggleRow(stringResource(R.string.settings_highlight_downloads), stringResource(R.string.settings_highlight_downloads_desc), settings.prominentDownloadButton, onToggleProminentDownloads)
            }
        }

        item {
            SettingsGroup(title = stringResource(R.string.settings_group_advanced)) {
                SettingsToggleRow(stringResource(R.string.settings_sign_out_on_stop), stringResource(R.string.settings_sign_out_on_stop_desc), settings.clearAuthOnStop, onToggleClearAuthOnStop)
                SettingsToggleRow(stringResource(R.string.settings_clear_temporary_files), stringResource(R.string.settings_clear_temporary_files_desc), settings.ghostMode, onToggleGhostMode)
                SettingsToggleRow(stringResource(R.string.settings_prevent_downloads), stringResource(R.string.settings_prevent_downloads_desc), settings.preventDownload, onTogglePreventDownload)
                ManualPortRow(currentPort = settings.preferredPort.toString(), onPortChanged = onPreferredPortChanged)
            }
        }

        item {
            SettingsGroup(title = stringResource(R.string.settings_group_network)) {
                SettingsChoiceRow(stringResource(R.string.settings_wifi_settings), stringResource(R.string.settings_wifi_settings_desc), onOpenWifiSettings)
                SettingsChoiceRow(stringResource(R.string.settings_hotspot_settings), stringResource(R.string.settings_hotspot_settings_desc), onOpenHotspotSettings)
            }
        }

        item {
            SettingsGroup(title = stringResource(R.string.settings_group_help)) {
                SettingsChoiceRow(stringResource(R.string.settings_view_onboarding), stringResource(R.string.settings_view_onboarding_desc), onViewOnboarding)
                SettingsChoiceRow(stringResource(R.string.settings_privacy_policy), stringResource(R.string.settings_privacy_policy_desc), onOpenPrivacyPolicy)
                SettingsChoiceRow(stringResource(R.string.settings_privacy_promise), stringResource(R.string.settings_privacy_promise_desc), onOpenHelp)
                SettingsChoiceRow(stringResource(R.string.settings_how_it_works), stringResource(R.string.settings_how_it_works_desc), onOpenHelp)
                SettingsChoiceRow(stringResource(R.string.settings_app_version), appVersionLabel, onClick = {})
                Text(
                    text = stringResource(R.string.settings_recent_shares_count, recentSessions.size),
                    modifier = Modifier.padding(horizontal = GhostSpacing.listItem, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (showDebugTools) {
            item {
                SettingsGroup(title = stringResource(R.string.settings_group_debug)) {
                    SettingsChoiceRow(stringResource(R.string.settings_share_debug_log), stringResource(R.string.settings_share_debug_log_desc), onShareDebugLog)
                    SettingsChoiceRow(stringResource(R.string.settings_clear_debug_log), debugLogLocation, onClearDebugLog)
                }
            }
        }

        item { Spacer(modifier = Modifier.height(GhostSpacing.section)) }
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
            .padding(
                horizontal = GhostSpacing.screenHorizontal,
                vertical = GhostSpacing.screenVertical,
            ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(GhostSpacing.section)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.help_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = ghostPanelColor()),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), // Slight lift helps the intro card read as the primary entry section.
            ) {
                Column(
                    modifier = Modifier.padding(GhostSpacing.heroCard),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(stringResource(R.string.help_about_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.help_about_1), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.help_about_2), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.help_about_3), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HelpSectionCard(
                title = stringResource(R.string.help_section_what_you_can_do),
                lines = listOf(
                    stringResource(R.string.help_line_share_files),
                    stringResource(R.string.help_line_open_session),
                    stringResource(R.string.help_line_same_network),
                    stringResource(R.string.help_line_multiple_browse),
                ),
            )
            HelpSectionCard(
                title = stringResource(R.string.help_section_video_playback),
                lines = listOf(
                    stringResource(R.string.help_line_video_direct),
                    stringResource(R.string.help_line_video_temporary),
                    stringResource(R.string.help_line_video_original),
                ),
            )
            HelpSectionCard(
                title = stringResource(R.string.help_section_receiving_files),
                lines = listOf(
                    stringResource(R.string.help_line_receive_upload),
                    stringResource(R.string.help_line_receive_approval),
                    stringResource(R.string.help_line_receive_history),
                ),
            )
            HelpSectionCard(
                title = stringResource(R.string.help_section_multiple_devices),
                lines = listOf(
                    stringResource(R.string.help_line_multi_connect),
                    stringResource(R.string.help_line_multi_live),
                    stringResource(R.string.help_line_multi_controls),
                ),
            )
            HelpSectionCard(
                title = stringResource(R.string.help_section_good_to_know),
                lines = listOf(
                    stringResource(R.string.help_line_wifi_hotspot),
                    stringResource(R.string.help_line_public_wifi),
                    stringResource(R.string.help_line_downloads_disabled),
                ),
            )
            HelpSectionCard(
                title = stringResource(R.string.help_section_quick_flow),
                lines = listOf(
                    stringResource(R.string.help_line_flow_1),
                    stringResource(R.string.help_line_flow_2),
                    stringResource(R.string.help_line_flow_3),
                    stringResource(R.string.help_line_flow_4),
                ),
            )
        }
    }
}

@Composable
fun PrivacyPolicyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(
                horizontal = GhostSpacing.screenHorizontal,
                vertical = GhostSpacing.screenVertical,
            ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(GhostSpacing.section)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.privacy_policy_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = ghostPanelColor()),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(
                    modifier = Modifier.padding(GhostSpacing.heroCard),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.privacy_policy_intro), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.privacy_policy_line_1), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.privacy_policy_line_2), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.privacy_policy_line_3), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.privacy_policy_line_4), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(GhostSpacing.card),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
            .padding(horizontal = GhostSpacing.screenHorizontal)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = ghostPanelColor()),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = GhostSpacing.listItem, vertical = 8.dp),
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
            .heightIn(min = 64.dp) // Gives the label and switch breathing room while keeping taps accessible.
            .padding(horizontal = GhostSpacing.listItem, vertical = 12.dp),
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
            .padding(horizontal = GhostSpacing.listItem, vertical = 8.dp)
            .fillMaxWidth(),
        label = { Text(stringResource(R.string.settings_pin_label)) },
        placeholder = { Text(stringResource(R.string.settings_pin_placeholder)) },
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
            .padding(horizontal = GhostSpacing.listItem, vertical = 8.dp)
            .fillMaxWidth(),
        label = { Text(stringResource(R.string.settings_sharing_port)) },
        placeholder = { Text(stringResource(R.string.settings_sharing_port_placeholder)) },
        supportingText = {
            Text(
                text = stringResource(R.string.settings_sharing_port_help),
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
            .heightIn(min = 56.dp) // Matches the rest of the settings list so rows scan and tap consistently.
            .padding(horizontal = GhostSpacing.listItem, vertical = 12.dp),
    ) {
        Text(title)
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> androidx.compose.ui.res.stringResource(R.string.settings_theme_system)
    ThemeMode.DARK -> androidx.compose.ui.res.stringResource(R.string.settings_theme_dark)
    ThemeMode.LIGHT -> androidx.compose.ui.res.stringResource(R.string.settings_theme_light)
}

@Composable
private fun ghostPanelColor(): androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface
