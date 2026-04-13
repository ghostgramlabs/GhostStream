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

        // ── DIRECT-safe video codecs ─────────────────────────────────────────────
        // Only H.264 / AVC is universally supported in every target browser and
        // Android WebView. HEVC is Safari-only and fails in Chrome/Firefox/most
        // Android WebViews. VP9/AV1/MPEG-2 are also excluded.
        val isDirectSafeVideo = !hasVideo ||
            inspection.videoTrackMimeType == "video/avc"

        // ── DIRECT-safe audio codecs ─────────────────────────────────────────────
        // AAC-LC and MP3 are universally safe. Opus works in fMP4 on Chrome but
        // fails in Safari and some Android WebViews, so we exclude it from DIRECT
        // to avoid silent failures on those devices.
        val isDirectSafeAudio = inspection.audioTrackMimeType == null ||
            inspection.audioTrackMimeType == "audio/mp4a-latm" ||
            inspection.audioTrackMimeType == "audio/mpeg" ||
            inspection.audioTrackMimeType == "audio/x-mp3"

        // ── REMUX-safe video codecs ───────────────────────────────────────────────
        // H.264 can be copied (remuxed) without re-encoding. HEVC, VP9, AV1, etc.
        // require a full video transcode to produce browser-safe H.264 output.
        val isRemuxSafeVideo = !hasVideo ||
            inspection.videoTrackMimeType == "video/avc"

        val videoLike = hasVideo ||
            inspection.originalMimeType?.startsWith("video/") == true ||
            inspection.normalizedMimeType?.startsWith("video/") == true

        return when {
            // ── Tier 0: DIRECT ────────────────────────────────────────────────────
            // Container is MP4 or MOV (QuickTime), video is H.264, audio is AAC/MP3.
            // Serve the original file immediately — zero preparation time.
            // MOV + H.264 + AAC is supported by all major browsers without conversion.
            (inspection.container == MediaContainer.MP4 ||
                inspection.container == MediaContainer.QUICKTIME) &&
                isDirectSafeVideo && isDirectSafeAudio -> PlaybackDecision(
                mode = PlaybackMode.DIRECT,
                browserMimeType = "video/mp4",
                reason = "Ready for direct browser playback (no conversion needed)",
            )

            // ── Tier 1 & 2: REMUX ────────────────────────────────────────────────
            // Video is H.264 — safe to copy with no re-encoding. One of:
            //   Tier 1: container is wrong (MKV/TS/AVI…) but audio is OK → pure remux.
            //   Tier 2: container or audio is wrong → remux + AAC audio transcode.
            // The worker (Media3FragmentedMp4CompatibilityWorker) handles both sub-cases
            // automatically based on whether the source audio is already AAC/MP3.
            // Both are fast because video encoding is skipped entirely.
            isRemuxSafeVideo && videoLike -> PlaybackDecision(
                mode = PlaybackMode.REMUX,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Lightning-fast optimization available",
                reason = "H.264 video will be copied; container/audio optimized for browser",
            )

            // ── Tier 3 & 4: TRANSCODE ─────────────────────────────────────────────
            // Video codec is NOT H.264 (HEVC, VP9, AV1, MPEG-2, VC-1, etc.).
            // A full video re-encode to H.264 is required.
            //   Tier 3: audio is already AAC/MP3 → copy audio, transcode video only.
            //   Tier 4: both streams are incompatible → transcode everything.
            // The worker handles the distinction automatically via isNativeAudio detection.
            videoLike -> PlaybackDecision(
                mode = PlaybackMode.TRANSCODE,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Compatibility conversion available",
                reason = "Video codec needs conversion to H.264 for browser playback",
            )

            // ── Non-video: PDF ────────────────────────────────────────────────────
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
