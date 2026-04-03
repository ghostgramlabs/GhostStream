package com.ghoststream.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class PlaybackMode {
    DIRECT,
    REMUX,
    TRANSCODE,
}

@Serializable
data class PlaybackDecision(
    val mode: PlaybackMode = PlaybackMode.DIRECT,
    val browserMimeType: String? = null,
    val compatibilityLabel: String? = null,
    val reason: String = "Ready for direct browser playback",
)

