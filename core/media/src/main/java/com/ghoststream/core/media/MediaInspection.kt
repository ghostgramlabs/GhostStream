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
)
