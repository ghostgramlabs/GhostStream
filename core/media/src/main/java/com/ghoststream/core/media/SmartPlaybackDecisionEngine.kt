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
            inspection.container == MediaContainer.QUICKTIME ||
            inspection.container == MediaContainer.AVI ||
            inspection.container == MediaContainer.FLV ||
            inspection.container == MediaContainer.WMV ||
            inspection.container == MediaContainer.WEBM ||
            inspection.container == MediaContainer.TS
        // Keep this in sync with AndroidMediaAnalyzer so the decision engine operates
        // on the same codec set.  VP8/VP9 are browser-native in WebM containers on
        // Chrome, Firefox, and Edge; Vorbis/Opus are the corresponding audio codecs.
        // Including them here means WebM+VP8/VP9 files are served DIRECT (no remux/
        // transcode) via the first branch in the when block below.
        val browserVideoCompatible = inspection.videoTrackMimeType == null ||
            inspection.videoTrackMimeType == "video/avc" ||
            inspection.videoTrackMimeType == "video/x-vnd.on2.vp8" ||
            inspection.videoTrackMimeType == "video/x-vnd.on2.vp9"
        val browserAudioCompatible = inspection.audioTrackMimeType == null ||
            inspection.audioTrackMimeType == "audio/mp4a-latm" ||
            inspection.audioTrackMimeType == "audio/mpeg" ||
            inspection.audioTrackMimeType == "audio/vorbis" ||
            inspection.audioTrackMimeType == "audio/opus"

        return when {
            // MP4/AAC/MP3 and WebM/VP8+Vorbis/VP9+Opus — all browser-native, serve directly.
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
