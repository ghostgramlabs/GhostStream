package com.ghoststream.core.media

enum class MediaContainer {
    MP4,
    MATROSKA,
    QUICKTIME,
    AVI,
    FLV,
    WMV,
    WEBM,
    TS,
    MPEG,
    THREE_GP,
    OGG_VIDEO,
    VOB,
    MPEG_AUDIO,
    AAC_AUDIO,
    IMAGE,
    PDF,
    OTHER,
}

data class MediaInspection(
    val originalMimeType: String?,
    val normalizedMimeType: String?,
    val displayName: String,
    val extension: String,
    val container: MediaContainer,
    val videoTrackMimeType: String? = null,
    val audioTrackMimeType: String? = null,
    val browserSafe: Boolean,
    val likelyContainerOnlyIssue: Boolean,
    val likelyNeedsTranscode: Boolean,
    val durationMs: Long? = null,
    /**
     * AVC/H.264 profile (e.g. 66=Baseline, 77=Main, 100=High).
     * Used to determine hardware decoder compatibility.
     */
    val videoProfile: Int? = null,
    /**
     * AVC/H.264 level (e.g. 31=3.1, 41=4.1, 52=5.2).
     * Used to determine hardware decoder throughput limits.
     */
    val videoLevel: Int? = null,
    /**
     * Bit depth (e.g. 8, 10). 10-bit video often requires transcoding for
     * web playback on older devices or specific browsers (iOS/Safari).
     */
    val bitDepth: Int? = null,
    /**
     * True if the MP4/MOV file has the moov atom before mdat (faststart-ready).
     * Null if not applicable (non-MP4/MOV container) or if detection failed.
     * When false, browser playback may require downloading the entire file before
     * seeking works, so a REMUX pass to relocate moov is recommended.
     */
    val hasFaststart: Boolean? = null,
)
