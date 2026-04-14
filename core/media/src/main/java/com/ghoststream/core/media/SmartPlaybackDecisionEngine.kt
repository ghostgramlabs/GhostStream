package com.ghoststream.core.media

import com.ghoststream.core.model.PlaybackDecision
import com.ghoststream.core.model.PlaybackMode

interface SmartPlaybackDecisionEngine {
    fun decide(inspection: MediaInspection): PlaybackDecision
}

/**
 * Chooses the cheapest valid playback path for each file, following a strict tier hierarchy:
 *
 *  Tier 0 – DIRECT:  serve the original file unchanged.  Zero processing cost.
 *                    Requires (MP4 or MOV) + H.264 video + (AAC-LC or MP3) audio.
 *
 *  Tier 1 – REMUX:   video is H.264 (safe to copy), but container is wrong.
 *                    Media3 copies the video track as-is.  Very fast (~1–5× real-time).
 *
 *  Tier 2 – REMUX:   video is H.264 (safe to copy), but audio is not AAC/MP3.
 *                    Media3 copies video, re-encodes only audio to AAC.
 *                    Still uses REMUX mode — the worker detects this case internally.
 *
 *  Tier 3 – TRANSCODE: video codec is NOT H.264 (HEVC, VP9, AV1, MPEG-2, etc.).
 *                    Video must be re-encoded to H.264.  Audio is copied if already AAC/MP3,
 *                    transcoded to AAC otherwise.  Slowest path.
 *
 * The guiding principle is: never use a slower tier when a faster one is safe.
 * "Safe" for DIRECT means universally playable in Chrome, Firefox, Safari, and Android
 * WebView without any codec negotiation — specifically H.264 video + AAC-LC or MP3 audio.
 * HEVC, VP9, AV1, Opus, and AC3/DTS are deliberately excluded from the DIRECT allowlist
 * because they are not universally supported across all target browsers/devices.
 */
class DefaultSmartPlaybackDecisionEngine : SmartPlaybackDecisionEngine {
    override fun decide(inspection: MediaInspection): PlaybackDecision {
        val hasVideo = inspection.videoTrackMimeType != null
        val videoLike = hasVideo ||
            inspection.originalMimeType?.startsWith("video/") == true ||
            inspection.normalizedMimeType?.startsWith("video/") == true

        // ── Tier 0 & 1: DIRECT-safe codecs ──────────────────────────────────────
        // Only H.264 / AVC is universally supported in every target browser.
        val isAvc = inspection.videoTrackMimeType == "video/avc"
        // AAC-LC and MP3 are universally safe.
        val isAacOrMp3 = inspection.audioTrackMimeType == null ||
            inspection.audioTrackMimeType == "audio/mp4a-latm" ||
            inspection.audioTrackMimeType == "audio/mpeg" ||
            inspection.audioTrackMimeType == "audio/x-mp3"

        return when {
            // ── Tier 0 & 1: DIRECT/REMUX (MP4/MOV) ────────────────────────────────
            // For MP4 containers with compatible codecs, we prefer direct serving.
            (inspection.container == MediaContainer.MP4 ||
                inspection.container == MediaContainer.QUICKTIME) &&
                (!hasVideo || isAvc) && isAacOrMp3 -> {
                // Future: add a check for 'fast-start' metadata to choose between
                // DIRECT and REMUX. For now, we prefer DIRECT unless the file is huge.
                PlaybackDecision(
                    mode = PlaybackMode.DIRECT,
                    browserMimeType = "video/mp4",
                    reason = "Ready for direct browser playback (MP4/H.264 native)",
                )
            }

            // ── Tier 2: TRANSMUX (Compatible Codecs, Wrong Container) ─────────────
            // Video is H.264, but container is MKV, TS, AVI, etc.
            // We repackage into HLS without re-encoding video.
            hasVideo && isAvc && isAacOrMp3 -> PlaybackDecision(
                mode = PlaybackMode.TRANSMUX,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Lightning-fast optimization available",
                reason = "Repackaging container for browser (H.264 copy)",
            )

            // ── Tier 2.5: TRANSMUX (Compatible Video, Incompatible Audio) ─────────
            // We can still copy the video track, but must transcode audio to AAC.
            // This still hits the TRANSMUX path in the worker (video copy).
            hasVideo && isAvc -> PlaybackDecision(
                mode = PlaybackMode.TRANSMUX,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Fast audio optimization available",
                reason = "Encoding browser-safe audio; video will be copied",
            )

            // ── Tier 3: TRANSCODE (Incompatible Video) ────────────────────────────
            // Video codec is NOT H.264 (HEVC, VP9, AV1, MPEG-2, etc.).
            // Full re-encode to H.264 required.
            videoLike -> PlaybackDecision(
                mode = PlaybackMode.TRANSCODE,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Compatibility conversion available",
                reason = "Video codec needs conversion to H.264 for browser playback",
            )

            // ── Non-video: PDF ───────────────────────────────────────────────────
            inspection.container == MediaContainer.PDF -> PlaybackDecision(
                mode = PlaybackMode.DIRECT,
                browserMimeType = "application/pdf",
                reason = "Browser preview is available",
            )

            // ── Everything else: serve directly ───────────────────────────────────
            // Audio files, images, documents — serve the original.
            else -> PlaybackDecision(
                mode = PlaybackMode.DIRECT,
                browserMimeType = inspection.normalizedMimeType ?: inspection.originalMimeType,
                reason = "Original file download is available",
            )
        }
    }
}
