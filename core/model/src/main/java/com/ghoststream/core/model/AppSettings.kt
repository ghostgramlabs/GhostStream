package com.ghoststream.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class AutoStopOption(val minutes: Int?) {
    NEVER(null),
    MINUTES_15(15),
    MINUTES_30(30),
    HOUR_1(60),
}

@Serializable
enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT,
}

@Serializable
data class AppSettings(
    val onboardingCompleted: Boolean = false,
    val languageSelectionCompleted: Boolean = false,
    val languageTag: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val autoStop: AutoStopOption = AutoStopOption.NEVER,
    val preferredPort: Int = 43183,
    val keepScreenAwake: Boolean = true,
    val hapticOnDeviceConnect: Boolean = true,
    val showRecentSessions: Boolean = true,
    val requireSessionPin: Boolean = false,
    val autoGeneratePin: Boolean = true,
    val manualPin: String = "2468",
    val clearAuthOnStop: Boolean = true,
    val ghostMode: Boolean = false,
    val showThumbnails: Boolean = true,
    val largeTvCards: Boolean = false,
    val prominentDownloadButton: Boolean = true,
    val requireUploadApproval: Boolean = true,
    val notifyOnDeviceConnect: Boolean = true,
    val notifyOnFileDownload: Boolean = true,
    val notifyOnUploadRequest: Boolean = true,
    val requireDeviceApproval: Boolean = false,
    val preventDownload: Boolean = false,
    val autoOptimizeLibrary: Boolean = false,
    val deviceNicknames: Map<String, String> = emptyMap(), // ipAddress -> nickname
)

@Serializable
data class RecentSession(
    val sessionId: String,
    val endedAtEpochMs: Long,
    val totalItems: Int,
    val totalBytesSent: Long,
    val networkType: NetworkType,
)
