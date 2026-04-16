package com.ghoststream.core.media

import com.ghoststream.core.model.PlaybackDecision
import com.ghoststream.core.model.PlaybackMode

/**
 * Intelligent decision engine for media playback.
 * 
 * Target: Gold Standard (H.264 / AAC / MP4) for universal compatibility.
 * Capabilities awareness allows bypassing transcoding for modern formats (HEVC, VP9, AV1)
 * when the client device explicitly supports them.
 */
interface SmartPlaybackDecisionEngine {
    fun decide(inspection: MediaInspection, capabilities: ClientCapabilities): PlaybackDecision
}

class DefaultSmartPlaybackDecisionEngine : SmartPlaybackDecisionEngine {
    override fun decide(inspection: MediaInspection, capabilities: ClientCapabilities): PlaybackDecision {
        val hasVideo = inspection.videoTrackMimeType != null
        val videoLike = hasVideo || 
            inspection.originalMimeType?.startsWith("video/") == true ||
            inspection.normalizedMimeType?.startsWith("video/") == true

        // Universal Support: H.264
        val isAvc = inspection.videoTrackMimeType == "video/avc"
        
        // Capability-Aware Direct Play (HEVC, VP9, AV1)
        val canDirectHevc = capabilities.supportsHevc && (inspection.videoTrackMimeType == "video/hevc" || inspection.videoTrackMimeType == "video/h265")
        val canDirectVp9 = capabilities.supportsVp9 && inspection.videoTrackMimeType == "video/x-vnd.on2.vp9"
        val canDirectAv1 = capabilities.supportsAv1 && inspection.videoTrackMimeType == "video/av01"

        // Audio support: Direct (AAC, MP3) vs Transcode
        val isAac = inspection.audioTrackMimeType?.contains("mp4a-latm") == true || inspection.audioTrackMimeType?.contains("aac") == true
        val isMp3 = inspection.audioTrackMimeType?.contains("mpeg") == true || inspection.audioTrackMimeType?.contains("mp3") == true

        val videoSafe = isAvc || canDirectHevc || canDirectVp9 || canDirectAv1
        val audioSafe = isAac || isMp3

        val containerSafe = inspection.container == MediaContainer.MP4 || inspection.container == MediaContainer.QUICKTIME
        val needsFaststartFix = inspection.hasFaststart == false // null means unknown or skip

        return when {
            // DIRECT: Best case, direct playback with range requests.
            containerSafe && videoSafe && audioSafe && !needsFaststartFix -> PlaybackDecision(
                mode = PlaybackMode.DIRECT,
                reason = "Directly compatible with your browser",
                browserMimeType = inspection.normalizedMimeType,
                compatibilityLabel = "Direct Play"
            )

            // REMUX: Container repair only. moov-at-front or audio swap.
            videoSafe && audioSafe -> PlaybackDecision(
                mode = PlaybackMode.REMUX,
                reason = if (needsFaststartFix) "Optimizing for instant stream start" else "Repairing container structure",
                browserMimeType = "video/mp4",
                compatibilityLabel = "Optimizing web stream"
            )

            // REMUX (Audio only): Video is fine, audio needs transcode to AAC.
            videoSafe -> PlaybackDecision(
                mode = PlaybackMode.REMUX,
                reason = "Preparing browser-compatible audio",
                browserMimeType = "video/mp4",
                compatibilityLabel = "Preparing audio"
            )

            // TRANSCODE: Video codec incompatible. Full re-encode to H.264.
            else -> PlaybackDecision(
                mode = PlaybackMode.TRANSCODE,
                reason = "Preparing browser-compatible video",
                browserMimeType = "video/mp4",
                compatibilityLabel = "Preparing video"
            )
        }
    }
}