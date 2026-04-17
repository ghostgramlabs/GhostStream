package com.ghoststream.core.media

/**
 * Validates if an HLS stream is "ready" to be served to a player.
 * 
 * To prevent playback stalls and "EXTM3U" manifest parsing errors in the browser,
 * we enforce a minimum buffered duration threshold (typically 4-6 seconds)
 * before we allow the server to return anything other than a "202 Accepted" status.
 */
object HlsReadinessValidator {

    private const val MIN_READINESS_DURATION_SECONDS = 4.0 // Threshold: 4 seconds
    private const val MIN_COMMITTED_SEGMENTS = 2
    private const val EXTM3U_HEADER = "#EXTM3U"

    data class ReadinessResult(
        val isReady: Boolean,
        val bufferedDurationSeconds: Double,
        val segmentCount: Int,
        val diagnosticInfo: String? = null
    )

    /**
     * Checks whether a growing fragmented MP4 is ready for direct browser playback.
     *
     * This is intentionally more permissive than HLS readiness: once the init segment and
     * the first committed fragment are available, the browser can begin playback from the
     * growing MP4 even while background finalization continues.
     */
    fun validateFragmentedMp4Playback(index: FragmentedMp4HlsIndex?): ReadinessResult {
        if (index == null) {
            return ReadinessResult(false, 0.0, 0, "No fragmented MP4 index available.")
        }

        val missingInitSegment = index.initSegmentLength <= 0L
        val missingFirstSegment = index.segments.isEmpty()
        val hasInvalidSegment = index.segments.firstOrNull()?.length?.let { it <= 0L } == true
        val bufferedDuration = index.segments.firstOrNull()?.durationSeconds ?: 0.0

        val diagnosticInfo = when {
            missingInitSegment -> "Init segment is not locked yet."
            missingFirstSegment -> "No committed fragmented MP4 segments are available yet."
            hasInvalidSegment -> "The first committed fragmented MP4 segment is empty."
            !index.diagnosticInfo.isNullOrBlank() -> index.diagnosticInfo
            else -> null
        }

        val isReady = !missingInitSegment && !missingFirstSegment && !hasInvalidSegment

        return ReadinessResult(
            isReady = isReady,
            bufferedDurationSeconds = bufferedDuration,
            segmentCount = index.segments.size,
            diagnosticInfo = if (!isReady) diagnosticInfo else null,
        )
    }

    /**
     * Checks if the given HLS index meets the minimum readiness criteria.
     */
    fun validate(index: FragmentedMp4HlsIndex?): ReadinessResult {
        if (index == null) {
            return ReadinessResult(false, 0.0, 0, "No HLS index available.")
        }

        val totalDuration = index.segments.sumOf { it.durationSeconds }
        val missingInitSegment = index.initSegmentLength <= 0L
        val insufficientSegments = index.segments.size < MIN_COMMITTED_SEGMENTS
        val insufficientDuration = totalDuration < MIN_READINESS_DURATION_SECONDS
        val missingCodecMetadata = index.videoCodecString.isNullOrBlank()
        val hasInvalidSegment = index.segments.take(MIN_COMMITTED_SEGMENTS).any { it.length <= 0L }

        val diagnosticInfo = when {
            missingInitSegment -> "Init segment is not locked yet."
            missingCodecMetadata -> "Stream metadata is incomplete."
            insufficientSegments -> "Only ${index.segments.size} committed segments are available."
            insufficientDuration -> "Buffered duration ($totalDuration s) is below threshold ($MIN_READINESS_DURATION_SECONDS s)."
            hasInvalidSegment -> "One or more committed segments are empty."
            !index.diagnosticInfo.isNullOrBlank() -> index.diagnosticInfo
            else -> null
        }

        val isReady = !missingInitSegment &&
            !missingCodecMetadata &&
            !insufficientSegments &&
            !insufficientDuration &&
            !hasInvalidSegment

        return ReadinessResult(
            isReady = isReady,
            bufferedDurationSeconds = totalDuration,
            segmentCount = index.segments.size,
            diagnosticInfo = if (!isReady) diagnosticInfo else null
        )
    }

    /**
     * Validates that a generated manifest string is well-formed.
     */
    fun isManifestWellFormed(manifestContent: String): Boolean {
        return manifestContent.trim().startsWith(EXTM3U_HEADER)
    }
}
