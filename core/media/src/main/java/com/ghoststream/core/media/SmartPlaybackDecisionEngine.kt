package com.ghoststream.core.media

import com.ghoststream.core.model.PlaybackDecision
import com.ghoststream.core.model.PlaybackMode

interface SmartPlaybackDecisionEngine {
    fun decide(inspection: MediaInspection): PlaybackDecision
}

class DefaultSmartPlaybackDecisionEngine : SmartPlaybackDecisionEngine {
    override fun decide(inspection: MediaInspection): PlaybackDecision {
        val videoLike = inspection.videoTrackMimeType != null ||
            inspection.originalMimeType?.startsWith("video/") == true ||
            inspection.normalizedMimeType?.startsWith("video/") == true ||
            inspection.container == MediaContainer.MP4 ||
            inspection.container == MediaContainer.MATROSKA ||
            inspection.container == MediaContainer.QUICKTIME
        val browserVideoCompatible = inspection.videoTrackMimeType == null || inspection.videoTrackMimeType == "video/avc"
        val browserAudioCompatible = inspection.audioTrackMimeType == null ||
            inspection.audioTrackMimeType == "audio/mp4a-latm" ||
            inspection.audioTrackMimeType == "audio/mpeg"

        return when {
            inspection.browserSafe && browserVideoCompatible && browserAudioCompatible -> PlaybackDecision(
                mode = PlaybackMode.DIRECT,
                browserMimeType = inspection.normalizedMimeType ?: inspection.originalMimeType,
                reason = "Ready for browser playback",
            )

            inspection.likelyContainerOnlyIssue && browserVideoCompatible && browserAudioCompatible -> PlaybackDecision(
                mode = PlaybackMode.REMUX,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Fragmented playback available",
                reason = "Container can be optimized for browser playback without re-encoding",
            )

            inspection.likelyNeedsTranscode -> PlaybackDecision(
                mode = PlaybackMode.TRANSCODE,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Compatibility conversion available",
                reason = "This format needs compatibility conversion for browser playback",
            )

            videoLike -> PlaybackDecision(
                mode = PlaybackMode.TRANSCODE,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Compatibility conversion available",
                reason = "Preparing this video for broader browser playback is recommended",
            )

            inspection.container == MediaContainer.PDF -> PlaybackDecision(
                mode = PlaybackMode.DIRECT,
                browserMimeType = "application/pdf",
                reason = "Browser preview is available",
            )

            else -> PlaybackDecision(
                mode = PlaybackMode.DIRECT,
                browserMimeType = inspection.normalizedMimeType ?: inspection.originalMimeType,
                reason = "Original file download is available",
            )
        }
    }
}
