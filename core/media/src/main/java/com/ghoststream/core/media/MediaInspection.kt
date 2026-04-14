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
     * True if the MP4/MOV file has the moov atom before mdat (faststart-ready).
     * Null if not applicable (non-MP4/MOV container) or if detection failed.
     * When false, browser playback may require downloading the entire file before
     * seeking works, so a REMUX pass to relocate moov is recommended.
     */
    val hasFaststart: Boolean? = null,
)
