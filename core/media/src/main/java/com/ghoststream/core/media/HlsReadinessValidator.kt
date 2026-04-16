package com.ghoststream.core.media

import java.io.File

/**
 * Validates if an HLS stream is "ready" to be served to a player.
 * 
 * To prevent playback stalls and "EXTM3U" manifest parsing errors in the browser,
 * we enforce a minimum buffered duration threshold (typically 4-6 seconds)
 * before we allow the server to return anything other than a "202 Accepted" status.
 */
object HlsReadinessValidator {

    private const val MIN_READINESS_DURATION_SECONDS = 4.0 // Threshold: 4 seconds
    private const val EXTM3U_HEADER = "#EXTM3U"

    data class ReadinessResult(
        val isReady: Boolean,
        val bufferedDurationSeconds: Double,
        val segmentCount: Int,
        val diagnosticInfo: String? = null
    )

    /**
     * Checks if the given HLS index meets the minimum readiness criteria.
     */
    fun validate(index: FragmentedMp4HlsIndex?): ReadinessResult {
        if (index == null) {
            return ReadinessResult(false, 0.0, 0, "No HLS index available.")
        }

        val totalDuration = index.segments.sumOf { it.durationSeconds }
        val isReady = totalDuration >= MIN_READINESS_DURATION_SECONDS && index.segments.isNotEmpty()

        return ReadinessResult(
            isReady = isReady,
            bufferedDurationSeconds = totalDuration,
            segmentCount = index.segments.size,
            diagnosticInfo = if (!isReady) {
                "Buffered duration ($totalDuration s) is below threshold ($MIN_READINESS_DURATION_SECONDS s)."
            } else null
        )
    }

    /**
     * Validates that a generated manifest string is well-formed.
     */
    fun isManifestWellFormed(manifestContent: String): Boolean {
        return manifestContent.trim().startsWith(EXTM3U_HEADER)
    }
}
