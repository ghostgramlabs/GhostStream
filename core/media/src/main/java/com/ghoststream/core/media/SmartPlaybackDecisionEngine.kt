package com.ghoststream.core.media

import com.ghoststream.core.model.PlaybackDecision
import com.ghoststream.core.model.PlaybackMode
import com.ghostgramlabs.directserve.core.resources.R

/**
 * Intelligent decision engine for media playback.
 * 
 * Target: Gold Standard (H.264 / AAC / MP4) for universal compatibility.
 * Capabilities awareness allows bypassing transcoding for modern formats (HEVC, VP9, AV1)
 * when the client device explicitly supports them.
 */
interface SmartPlaybackDecisionEngine {
    fun decide(
        inspection: MediaInspection,
        capabilities: ClientCapabilities = ClientCapabilities.DEFAULT,
        directPlaybackFailed: Boolean = false,
    ): PlaybackDecision
}

class DefaultSmartPlaybackDecisionEngine : SmartPlaybackDecisionEngine {
    override fun decide(
        inspection: MediaInspection,
        capabilities: ClientCapabilities,
        directPlaybackFailed: Boolean,
    ): PlaybackDecision {
        val hasVideo = inspection.videoTrackMimeType != null
        val videoLike = hasVideo ||
            inspection.originalMimeType?.startsWith("video/") == true ||
            inspection.normalizedMimeType?.startsWith("video/") == true

        if (!videoLike) {
            return PlaybackDecision(
                mode = PlaybackMode.DIRECT,
                browserMimeType = inspection.originalMimeType ?: inspection.normalizedMimeType,
                compatibilityLabel = "Direct Play",
                labelResId = R.string.playback_mode_direct,
                reason = "Non-video item bypasses the compatibility pipeline",
                reasonResId = R.string.playback_reason_direct_non_video,
            ).also { logDecision(inspection, capabilities, it, remuxEligible = false, transcodeReason = null) }
        }

        val containerSafe = inspection.container == MediaContainer.MP4 || inspection.container == MediaContainer.QUICKTIME
        val videoCodec = normalizeVideoCodec(inspection.videoTrackMimeType)
        val audioCodec = normalizeAudioCodec(inspection.audioTrackMimeType)
        val directContainerSafe = !directPlaybackFailed && isDirectContainerSafe(inspection, videoCodec, audioCodec, capabilities)
        val mp4RemuxEligible = isMp4RemuxEligible(inspection, videoCodec, capabilities)

        val hasKnownVideoIncompatibility = when (videoCodec) {
            VideoCodec.AVC, VideoCodec.UNKNOWN -> false
            VideoCodec.HEVC -> !capabilities.supportsHevc
            VideoCodec.VP9 -> !capabilities.supportsVp9
            VideoCodec.AV1 -> !capabilities.supportsAv1
            VideoCodec.OTHER -> true
        }
        val hasKnownAudioIncompatibility = when (audioCodec) {
            AudioCodec.NONE, AudioCodec.AAC, AudioCodec.MP3, AudioCodec.UNKNOWN -> false
            AudioCodec.OPUS -> !capabilities.supportsOpus
            AudioCodec.AC3 -> !capabilities.supportsAc3
            AudioCodec.EAC3 -> !capabilities.supportsEac3
            AudioCodec.OTHER -> true
        }

        val needsVideoTranscode =
            hasKnownVideoIncompatibility ||
                (inspection.bitDepth != null && inspection.bitDepth > 8 && !capabilities.supportsHdr) ||
                (inspection.hdrFormat != null && !capabilities.supportsHdr)
        // Missing/corrupt AAC csd-0 cannot be fixed by a copy mux — the audio init
        // segment will fail in the browser. Force a re-encode so MediaCodec emits a
        // valid AudioSpecificConfig.
        val needsAudioTranscode = hasKnownAudioIncompatibility ||
            inspection.audioInitDataOk == false
        val transcodeReason = when {
            inspection.container == MediaContainer.WEBM && !directContainerSafe && !mp4RemuxEligible ->
                "WEBM source is not directly browser-safe for this client and cannot be repackaged into a reliable MP4 stream"
            !mp4RemuxEligible && !directContainerSafe ->
                "Container/codec combination is not safe to copy into browser-ready MP4 output"
            needsVideoTranscode ->
                "Video codec is not browser-safe for this client and must be re-encoded"
            else -> null
        }
        val transcodeReasonResId = when {
            inspection.container == MediaContainer.WEBM && !directContainerSafe && !mp4RemuxEligible ->
                R.string.playback_reason_transcode_webm_fix
            !mp4RemuxEligible && !directContainerSafe ->
                R.string.playback_reason_transcode_unsupported
            needsVideoTranscode ->
                R.string.playback_reason_transcode_video_incompatible
            else -> null
        }

        val decision = when {
            // Modern browsers (Safari, Chrome, Firefox, Edge) handle moov-at-end
            // MP4/MOV via HTTP Range requests — they fetch the tail to locate the
            // moov atom, then stream from the start. Forcing REMUX for faststart
            // alone is wasted CPU/disk on the host.
            directContainerSafe && !needsVideoTranscode && !needsAudioTranscode -> PlaybackDecision(
                mode = PlaybackMode.DIRECT,
                browserMimeType = directMimeTypeFor(inspection),
                compatibilityLabel = "Direct Play",
                labelResId = R.string.playback_mode_direct,
                reason = "Container and codecs are browser-safe for this client",
                reasonResId = R.string.playback_reason_direct_safe,
            )

            mp4RemuxEligible && !needsVideoTranscode -> PlaybackDecision(
                mode = PlaybackMode.TRANSMUX,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Repackaging",
                labelResId = R.string.playback_mode_repackaging,
                reason = when {
                    !containerSafe && needsAudioTranscode ->
                        "Container is not browser-safe and audio must be converted during repackaging"
                    !containerSafe ->
                        "Codecs are acceptable, but the container must be repackaged for the browser"
                    needsAudioTranscode ->
                        "Video can be copied, but audio must be converted to a browser-safe format"
                    else ->
                        "Packaging fix required before browser playback"
                },
                reasonResId = when {
                    !containerSafe && needsAudioTranscode ->
                        R.string.playback_reason_remux_audio_fix
                    !containerSafe ->
                        R.string.playback_reason_remux_container_fix
                    needsAudioTranscode ->
                        R.string.playback_reason_remux_audio_only
                    else ->
                        R.string.playback_reason_remux_generic
                }
            )

            else -> PlaybackDecision(
                mode = PlaybackMode.TRANSCODE,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Transcoding",
                labelResId = R.string.playback_mode_transcoding,
                reason = transcodeReason ?: "Video codec is not browser-safe for this client and must be re-encoded",
                reasonResId = transcodeReasonResId ?: R.string.playback_reason_transcode_video_incompatible,
            )
        }

        logDecision(inspection, capabilities, decision, mp4RemuxEligible, transcodeReason)
        return decision
    }

    private fun directMimeTypeFor(inspection: MediaInspection): String? {
        return when {
            inspection.container == MediaContainer.WEBM -> "video/webm"
            inspection.originalMimeType?.startsWith("video/") == true &&
                (inspection.container == MediaContainer.MP4 || inspection.container == MediaContainer.QUICKTIME) -> "video/mp4"
            else -> inspection.originalMimeType ?: inspection.normalizedMimeType
        }
    }

    private fun isDirectContainerSafe(
        inspection: MediaInspection,
        videoCodec: VideoCodec,
        audioCodec: AudioCodec,
        capabilities: ClientCapabilities,
    ): Boolean {
        // H.264 and many MP4-bound decoders reject odd dimensions; reject DIRECT
        // when we can prove odd, but stay permissive when width/height are unknown.
        val width = inspection.width
        val height = inspection.height
        val oddDims = (width != null && width % 2 != 0) || (height != null && height % 2 != 0)
        if (oddDims) return false
        // Missing AAC init data cannot be served as DIRECT — browser will fail audio decode.
        if (inspection.audioInitDataOk == false) return false
        return when (inspection.container) {
            MediaContainer.MP4,
            MediaContainer.QUICKTIME -> true
            MediaContainer.WEBM -> when (videoCodec) {
                VideoCodec.VP9 -> capabilities.supportsVp9 && audioCodec in setOf(AudioCodec.NONE, AudioCodec.OPUS, AudioCodec.UNKNOWN)
                VideoCodec.AV1 -> capabilities.supportsAv1 && audioCodec in setOf(AudioCodec.NONE, AudioCodec.OPUS, AudioCodec.UNKNOWN)
                else -> false
            }
            MediaContainer.MATROSKA ->
                capabilities.supportsMatroska &&
                    videoCodec in setOf(VideoCodec.AVC, VideoCodec.HEVC, VideoCodec.VP9, VideoCodec.AV1, VideoCodec.UNKNOWN) &&
                    audioCodec in setOf(AudioCodec.NONE, AudioCodec.AAC, AudioCodec.MP3, AudioCodec.OPUS, AudioCodec.UNKNOWN)
            else -> false
        }
    }

    private fun isMp4RemuxEligible(
        inspection: MediaInspection,
        videoCodec: VideoCodec,
        capabilities: ClientCapabilities,
    ): Boolean {
        // Allow HEVC remux when the client signals HEVC support (Safari/iOS, Edge, modern Chrome).
        // The Media3-driven worker emits an fMP4 with `hvc1` codec strings.
        //
        // UNKNOWN video handling depends on the container's typical content:
        //   MKV/WebM/FLV/TS — H.264/H.265/VP9/AV1 dominant, treat as copyable so the worker
        //     can confirm and proceed without UI flicker.
        //   AVI/MPEG       — historically MPEG-1/2 / Xvid / DivX which Media3 InAppMuxer
        //     cannot copy. Default to pessimistic so the engine matches the worker's eventual
        //     decision; the worker still upgrades TRANSCODE → TRANSMUX if it finds H.264.
        val unknownTreatedAsCopyable = when (inspection.container) {
            MediaContainer.MATROSKA,
            MediaContainer.WEBM,
            MediaContainer.FLV,
            MediaContainer.TS -> true
            MediaContainer.AVI,
            MediaContainer.MPEG -> false
            else -> false
        }
        val nonMp4VideoCopyable = videoCodec == VideoCodec.AVC ||
            (videoCodec == VideoCodec.UNKNOWN && unknownTreatedAsCopyable) ||
            (videoCodec == VideoCodec.HEVC && capabilities.supportsHevc)
        return when (inspection.container) {
            MediaContainer.MATROSKA,
            MediaContainer.AVI,
            MediaContainer.FLV,
            MediaContainer.TS,
            MediaContainer.MPEG,
            MediaContainer.WEBM -> nonMp4VideoCopyable
            MediaContainer.MP4,
            MediaContainer.QUICKTIME -> true
            else -> false
        }
    }

    private fun normalizeVideoCodec(mime: String?): VideoCodec {
        val normalized = mime?.lowercase().orEmpty()
        return when {
            normalized.isBlank() -> VideoCodec.UNKNOWN
            normalized == "video/avc" || normalized.contains("h264") || normalized.contains("avc1") -> VideoCodec.AVC
            normalized == "video/hevc" || normalized.contains("h265") || normalized.contains("hvc1") || normalized.contains("hev1") -> VideoCodec.HEVC
            normalized.contains("vp9") -> VideoCodec.VP9
            normalized.contains("av01") || normalized.contains("av1") -> VideoCodec.AV1
            else -> VideoCodec.OTHER
        }
    }

    private fun normalizeAudioCodec(mime: String?): AudioCodec {
        val normalized = mime?.lowercase().orEmpty()
        return when {
            normalized.isBlank() -> AudioCodec.UNKNOWN
            normalized.contains("mp4a-latm") || normalized.contains("aac") -> AudioCodec.AAC
            normalized.contains("mpeg") || normalized.contains("mp3") -> AudioCodec.MP3
            normalized.contains("opus") -> AudioCodec.OPUS
            normalized.contains("eac3") -> AudioCodec.EAC3
            normalized.contains("ac3") -> AudioCodec.AC3
            else -> AudioCodec.OTHER
        }
    }

    private fun logDecision(
        inspection: MediaInspection,
        capabilities: ClientCapabilities,
        decision: PlaybackDecision,
        remuxEligible: Boolean,
        transcodeReason: String?,
    ) {
        CompatLogger.info(
            "PlaybackDecision",
            "item=${inspection.itemId ?: "unknown"} ext=${inspection.extension} " +
                "container=${inspection.container} video=${inspection.videoTrackMimeType ?: "unknown"} " +
                "audio=${inspection.audioTrackMimeType ?: "unknown"} faststart=${inspection.hasFaststart} " +
                "target=${capabilities.browserFamily ?: "unknown"}-${capabilities.os ?: "unknown"} " +
                "mode=${decision.mode} remuxEligible=$remuxEligible " +
                "transcodeReason=\"${transcodeReason ?: ""}\" reason=\"${decision.reason}\"",
        )
    }

    private enum class VideoCodec {
        UNKNOWN,
        AVC,
        HEVC,
        VP9,
        AV1,
        OTHER,
    }

    private enum class AudioCodec {
        NONE,
        UNKNOWN,
        AAC,
        MP3,
        OPUS,
        AC3,
        EAC3,
        OTHER,
    }
}
