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
data class AppSettings(
    val onboardingCompleted: Boolean = false,
    val autoStop: AutoStopOption = AutoStopOption.NEVER,
    val preferredPort: Int = 43183,
    val keepScreenAwake: Boolean = true,
    val hapticOnDeviceConnect: Boolean = true,
    val showTransferSpeed: Boolean = true,
    val showRecentSessions: Boolean = true,
    val requireSessionPin: Boolean = false,
    val autoGeneratePin: Boolean = true,
    val manualPin: String = "2468",
    val clearAuthOnStop: Boolean = true,
    val ghostMode: Boolean = true,
    val forceDarkBrowserTheme: Boolean = true,
    val showThumbnails: Boolean = true,
    val largeTvCards: Boolean = false,
    val prominentDownloadButton: Boolean = true,
)

@Serializable
data class RecentSession(
    val sessionId: String,
    val endedAtEpochMs: Long,
    val totalItems: Int,
    val totalBytesSent: Long,
    val networkType: NetworkType,
)
