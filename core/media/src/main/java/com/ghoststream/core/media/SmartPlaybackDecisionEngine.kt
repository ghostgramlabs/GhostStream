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

        /**
         * "Strict" compatibility checks for universal reach (Android, iOS, TV, Desktop).
         * We target H.264 Main Profile @ Level 4.1 as the upper bound for non-transcode paths.
         * Profile 66=Baseline, 77=Main, 100=High.
         * Level 41 = 4.1 (supports 1080p @ 30fps).
         */
        val isSafeProfile = inspection.videoProfile == null || 
            inspection.videoProfile <= 100 // High profile and below
        
        val isSafeLevel = inspection.videoLevel == null ||
            inspection.videoLevel <= 41 // Level 4.1 and below

        // 8-bit is universal. 10-bit (HDR) requires transcoding for most web decoders.
        val is8Bit = inspection.bitDepth == null || inspection.bitDepth <= 8

        // AAC-LC and MP3 are universally safe.
        val isAacOrMp3 = inspection.audioTrackMimeType == null ||
            inspection.audioTrackMimeType == "audio/mp4a-latm" ||
            inspection.audioTrackMimeType == "audio/mpeg" ||
            inspection.audioTrackMimeType == "audio/x-mp3"

        val isBrowserContainer = inspection.container == MediaContainer.MP4 ||
            inspection.container == MediaContainer.QUICKTIME

        val isFullyUniversal = isAvc && isSafeProfile && isSafeLevel && is8Bit

        return when {
            // ── Tier 0: DIRECT ──────────────────────────────────────────────────
            // Browser-safe container + codecs + profiles + bit-depth.
            // hasFaststart != false: allow DIRECT when confirmed good (true) OR
            // unknown (null). detectFaststart() reads raw bytes — only works on
            // file:// URIs. content:// URIs from MediaStore/SAF return null
            // (no byte-range access). null must not force unnecessary REMUX for
            // every video. Only block DIRECT when bad moov is CONFIRMED (false).
            isBrowserContainer && (!hasVideo || isFullyUniversal) && isAacOrMp3 &&
                inspection.hasFaststart != false -> {
                PlaybackDecision(
                    mode = PlaybackMode.DIRECT,
                    browserMimeType = "video/mp4",
                    reason = if (inspection.hasFaststart == true)
                        "Ready for direct browser playback (H.264/AAC, faststart confirmed)"
                    else
                        "Ready for direct browser playback (H.264/AAC native)",
                )
            }

            // ── Tier 1a: REMUX (Confirmed bad faststart in MP4/MOV) ─────────────
            // Codecs are fine but moov atom is confirmed at end of file.
            isBrowserContainer && (!hasVideo || isFullyUniversal) && isAacOrMp3 &&
                inspection.hasFaststart == false -> {
                PlaybackDecision(
                    mode = PlaybackMode.REMUX,
                    browserMimeType = "video/mp4",
                    compatibilityLabel = "Quick optimization needed",
                    reason = "Relocating metadata for instant browser playback (faststart fix)",
                )
            }

            // ── Tier 1b: TRANSMUX (MP4/MOV container, incompatible audio) ────────
            // Video is H.264 and safe; audio needs re-encoding to AAC.
            // Copy video stream, transcode audio only, output non-fragmented MP4.
            isBrowserContainer && hasVideo && isFullyUniversal && !isAacOrMp3 -> {
                PlaybackDecision(
                    mode = PlaybackMode.TRANSMUX,
                    browserMimeType = "video/mp4",
                    compatibilityLabel = "Audio conversion needed",
                    reason = "Re-encoding audio to AAC for browser compatibility; video copied unchanged",
                )
            }

            // ── Tier 2a: TRANSMUX (Safe H.264 + audio, wrong container) ────────
            // Codecs are fine, but container is MKV/TS/AVI etc.
            hasVideo && isFullyUniversal && isAacOrMp3 -> PlaybackDecision(
                mode = PlaybackMode.TRANSMUX,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Lightning-fast optimization available",
                reason = "Repackaging ${inspection.container.name} container for browser (H.264 copy)",
            )

            // ── Tier 2b: TRANSMUX (Safe H.264, incompatible audio, wrong container)
            hasVideo && isFullyUniversal -> PlaybackDecision(
                mode = PlaybackMode.TRANSMUX,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Fast optimization available",
                reason = "Repackaging container and encoding browser-safe audio; video will be copied",
            )

            // ── Tier 3: TRANSCODE (Incompatible Video Codec or Profile/Level) ───
            // Full re-encode to Universal Standard (H.264 Main 4.1, 8-bit).
            videoLike -> {
                val reason = when {
                    !isAvc -> "Video codec (${inspection.videoTrackMimeType ?: "unknown"}) needs conversion to H.264"
                    !is8Bit -> "10-bit video (HDR) needs conversion to 8-bit for browser compatibility"
                    !isSafeProfile -> "Video profile (${inspection.videoProfile}) exceeds mobile/TV hardware limits"
                    !isSafeLevel -> "Video level (${inspection.videoLevel}) exceeds mobile/TV hardware limits"
                    else -> "Re-encoding for universal browser compatibility"
                }
                PlaybackDecision(
                    mode = PlaybackMode.TRANSCODE,
                    browserMimeType = "video/mp4",
                    compatibilityLabel = "Universal conversion needed",
                    reason = reason,
                )
            }

            // ── Fallback: Non-video files ────────────────────────────────────
            else -> PlaybackDecision(
                mode = PlaybackMode.DIRECT,
                browserMimeType = inspection.normalizedMimeType ?: "application/octet-stream",
                reason = "Non-video file; serving directly",
            )
        }
    }
}