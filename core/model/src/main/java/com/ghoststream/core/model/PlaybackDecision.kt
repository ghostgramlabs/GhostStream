package com.ghoststream.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class PlaybackMode {
    /** Serve the original file direct to the browser with zero intervention. */
    DIRECT,
    /** Optimize the container/metadata of an MP4 (e.g. fast-start) without re-encoding. */
    REMUX,
    /** Packaging into HLS segments without re-encoding video. (Container/MSE compatibility). */
    TRANSMUX,
    /** Full re-encode to H.264/AAC inside HLS segments. */
    TRANSCODE,
}

@Serializable
data class PlaybackDecision(
    val mode: PlaybackMode = PlaybackMode.DIRECT,
    val browserMimeType: String? = null,
    val compatibilityLabel: String? = null,
    val reason: String = "Ready for direct browser playback",
)

