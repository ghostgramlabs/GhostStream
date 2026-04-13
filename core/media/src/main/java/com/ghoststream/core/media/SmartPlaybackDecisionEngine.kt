package com.ghoststream.core.media

import com.ghoststream.core.model.PlaybackDecision
import com.ghoststream.core.model.PlaybackMode

interface SmartPlaybackDecisionEngine {
    fun decide(inspection: MediaInspection): PlaybackDecision
}

class DefaultSmartPlaybackDecisionEngine : SmartPlaybackDecisionEngine {
    override fun decide(inspection: MediaInspection): PlaybackDecision {
        val hasVideo = inspection.videoTrackMimeType != null
        val hasAudio = inspection.audioTrackMimeType != null
        
        // Browsers universally support H.264 (AVC).
        // HEVC (H.265) is supported by Safari and modern Chrome/Edge with hardware acceleration.
        val isBrowserCompatibleVideo = inspection.videoTrackMimeType == "video/avc" ||
            inspection.videoTrackMimeType == "video/hevc" ||
            inspection.videoTrackMimeType == "video/hvc1"

        // AAC-LC is the standard for browser-compatible MP4.
        val isBrowserCompatibleAudio = inspection.audioTrackMimeType == "audio/mp4a-latm" ||
            inspection.audioTrackMimeType == "audio/mpeg" || // MP3 is safe
            inspection.audioTrackMimeType == "audio/opus"   // Supported in fMP4

        val videoLike = hasVideo ||
            inspection.originalMimeType?.startsWith("video/") == true ||
            inspection.normalizedMimeType?.startsWith("video/") == true

        return when {
            // 1. Direct Play: MP4 container + Perfectly compatible streams.
            inspection.container == MediaContainer.MP4 && isBrowserCompatibleVideo && (!hasAudio || isBrowserCompatibleAudio) -> PlaybackDecision(
                mode = PlaybackMode.DIRECT,
                browserMimeType = "video/mp4",
                reason = "Ready for direct browser playback (MP4 container with compatible tracks)",
            )

            // 2. Remux: Compatible video stream but needs container adjustment or audio re-encoding.
            // If the video is compatible, we can use "Video Passthrough" which is near-instant.
            isBrowserCompatibleVideo -> PlaybackDecision(
                mode = PlaybackMode.REMUX,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Lightning-fast optimization available",
                reason = "Video track is browser-compatible; will copy video and optimize if needed",
            )

            // 3. Transcode: Incompatible video stream (e.g., VP9 in MKV, MPEG2, AV1 on older devices).
            videoLike -> PlaybackDecision(
                mode = PlaybackMode.TRANSCODE,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Compatibility conversion available",
                reason = "This format needs full conversion for browser playback",
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
