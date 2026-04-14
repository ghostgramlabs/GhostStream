package com.ghoststream.core.media

import com.ghoststream.core.model.PlaybackDecision
import com.ghoststream.core.model.PlaybackMode

interface SmartPlaybackDecisionEngine {
    fun decide(inspection: MediaInspection): PlaybackDecision
}

/**
 * Chooses the cheapest valid playback path for each file, following a strict 4-tier hierarchy:
 *
 *  Tier 0 – DIRECT:     Serve the original file unchanged. Zero processing cost.
 *                        Requires browser-safe container (MP4/MOV) + H.264 video +
 *                        (AAC-LC or MP3) audio + moov-before-mdat (faststart).
 *
 *  Tier 1 – REMUX:      Codecs are browser-safe, but container needs light fixing.
 *                        Copies video/audio as-is, rewrites container structure.
 *                        Very fast (~1–5× real-time). No quality loss.
 *                        Cases: bad moov placement in MP4, or incompatible audio
 *                        in an otherwise browser-safe file.
 *
 *  Tier 2 – TRANSMUX:   Video codec is browser-safe (H.264), but container is wrong
 *                        (MKV, TS, AVI, etc.). Repackage into browser-friendly fMP4/HLS
 *                        without re-encoding video. Audio may be transcoded to AAC if needed.
 *                        Fast, no video quality loss.
 *
 *  Tier 3 – TRANSCODE:  Video codec is NOT browser-safe (HEVC, VP9, AV1, MPEG-2, etc.).
 *                        Full re-encode to H.264 + AAC. Slowest, most CPU-intensive path.
 *                        Only used when no cheaper path is available.
 *
 * The guiding principle: never use a slower tier when a faster one is safe.
 * "Safe" for DIRECT means universally playable in Chrome, Firefox, Safari, and Android
 * WebView without any codec negotiation — specifically H.264 video + AAC-LC or MP3 audio
 * inside a properly structured MP4/MOV container with faststart moov placement.
 */
class DefaultSmartPlaybackDecisionEngine : SmartPlaybackDecisionEngine {
    override fun decide(inspection: MediaInspection): PlaybackDecision {
        val hasVideo = inspection.videoTrackMimeType != null
        val videoLike = hasVideo ||
            inspection.originalMimeType?.startsWith("video/") == true ||
            inspection.normalizedMimeType?.startsWith("video/") == true

        // Only H.264 / AVC is universally supported in every target browser.
        val isAvc = inspection.videoTrackMimeType == "video/avc"
        // AAC-LC and MP3 are universally safe.
        val isAacOrMp3 = inspection.audioTrackMimeType == null ||
            inspection.audioTrackMimeType == "audio/mp4a-latm" ||
            inspection.audioTrackMimeType == "audio/mpeg" ||
            inspection.audioTrackMimeType == "audio/x-mp3"

        val isBrowserContainer = inspection.container == MediaContainer.MP4 ||
            inspection.container == MediaContainer.QUICKTIME

        return when {
            // ── Tier 0: DIRECT ──────────────────────────────────────────────────
            // Browser-safe container + codecs + faststart moov placement.
            // Serve the original file as-is with HTTP range support.
            isBrowserContainer && (!hasVideo || isAvc) && isAacOrMp3 &&
                inspection.hasFaststart != false -> {
                PlaybackDecision(
                    mode = PlaybackMode.DIRECT,
                    browserMimeType = "video/mp4",
                    reason = "Ready for direct browser playback (MP4/H.264 native)",
                )
            }

            // ── Tier 1a: REMUX (Bad faststart in MP4/MOV) ───────────────────────
            // Codecs are fine but moov atom is at the end of the file.
            // Rewrite as faststart MP4 — no codec re-encode, just container fix.
            isBrowserContainer && (!hasVideo || isAvc) && isAacOrMp3 &&
                inspection.hasFaststart == false -> {
                PlaybackDecision(
                    mode = PlaybackMode.REMUX,
                    browserMimeType = "video/mp4",
                    compatibilityLabel = "Quick optimization needed",
                    reason = "Relocating metadata for instant browser playback (faststart fix)",
                )
            }

            // ── Tier 1b: REMUX (MP4/MOV container, incompatible audio) ──────────
            // Video is H.264 (safe), container is MP4/MOV, but audio needs re-encode.
            // Copy video as-is, transcode only audio to AAC. Still fast.
            isBrowserContainer && hasVideo && isAvc && !isAacOrMp3 -> {
                PlaybackDecision(
                    mode = PlaybackMode.REMUX,
                    browserMimeType = "video/mp4",
                    compatibilityLabel = "Quick audio fix needed",
                    reason = "Re-encoding audio to AAC; video stays unchanged",
                )
            }

            // ── Tier 2a: TRANSMUX (H.264 + compatible audio, wrong container) ───
            // Video is H.264, audio is AAC/MP3, but container is MKV, TS, AVI, etc.
            // Repackage into browser-friendly fMP4. No codec re-encode.
            hasVideo && isAvc && isAacOrMp3 -> PlaybackDecision(
                mode = PlaybackMode.TRANSMUX,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Lightning-fast optimization available",
                reason = "Repackaging ${inspection.container.name} container for browser (H.264 copy)",
            )

            // ── Tier 2b: TRANSMUX (H.264, incompatible audio, wrong container) ──
            // Copy video, transcode audio, repackage container.
            hasVideo && isAvc -> PlaybackDecision(
                mode = PlaybackMode.TRANSMUX,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Fast optimization available",
                reason = "Repackaging container and encoding browser-safe audio; video will be copied",
            )

            // ── Tier 3: TRANSCODE (Incompatible Video Codec) ────────────────────
            // Video codec is NOT H.264 (HEVC, VP9, AV1, MPEG-2, etc.).
            // Full re-encode to H.264 required. Last resort.
            videoLike -> PlaybackDecision(
                mode = PlaybackMode.TRANSCODE,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Compatibility conversion needed",
                reason = "Video codec (${inspection.videoTrackMimeType ?: "unknown"}) needs conversion to H.264 for browser",
            )

            // ── Non-video: PDF ──────────────────────────────────────────────────
            inspection.container == MediaContainer.PDF -> PlaybackDecision(
                mode = PlaybackMode.DIRECT,
                browserMimeType = "application/pdf",
                reason = "Browser preview is available",
            )

            // ── Everything else: serve directly ─────────────────────────────────
            // Audio files, images, documents — serve the original.
            else -> PlaybackDecision(
                mode = PlaybackMode.DIRECT,
                browserMimeType = inspection.normalizedMimeType ?: inspection.originalMimeType,
                reason = "Original file download is available",
            )
        }
    }
}
