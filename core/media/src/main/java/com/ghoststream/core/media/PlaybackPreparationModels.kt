package com.ghoststream.core.media

import com.ghoststream.core.model.PlaybackDecision
import kotlinx.serialization.Serializable

@Serializable
enum class CompatibilityStatus {
    IDLE,
    QUEUED,
    ANALYZING,
    PREPARING,
    FINALIZING,
    PLAYABLE_NOW,
    READY,
    FAILED,
    STALLED,
}

@Serializable
enum class JobPriority {
    /** Seeking into the same item — highest priority, reset state. */
    CRITICAL,
    /** Explicit user click to play — high priority. */
    HIGH,
    /** Background/passive warmup — lowest priority, instantly preemptable. */
    LOW,
}

@Serializable
enum class EffectivePlaybackMode {
    /** A compatibility path exists, but no playback source is ready yet. */
    PENDING,
    /** Playing the original source file. */
    DIRECT,
    /** Playing via a growing HLS stream. */
    LIVE_HLS,
    /** Playing a fully prepared/finalized compatible MP4 file. */
    PREPARED_MP4,
}

@Serializable
data class CachedPlaybackAsset(
    val itemId: String,
    val filePath: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val createdAtEpochMs: Long,
    val isComplete: Boolean = true,
    val isFragmentedMp4: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
    val totalDurationMs: Long? = null,
)

@Serializable
data class CompatibilityJob(
    val itemId: String,
    val decision: PlaybackDecision,
    val status: CompatibilityStatus = CompatibilityStatus.IDLE,
    val message: String = decision.reason,
    val messageResId: Int? = decision.reasonResId,
    val messageArgs: List<String> = emptyList(),
    val progressPercent: Int? = null,
    val preparedAsset: CachedPlaybackAsset? = null,
    val hlsReady: Boolean = false,
    val directReady: Boolean = preparedAsset?.isComplete ?: false,
    val streamable: Boolean = hlsReady || directReady,
    val startOffsetMs: Long = 0L,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    // Stuck detection fields
    val lastProgressAt: Long = System.currentTimeMillis(),
    val lastProgressValue: Int = 0,
    val lastOutputBytesAt: Long = System.currentTimeMillis(),
    val stallCheckCount: Int = 0,
    val stabilizedSource: StabilizedSourceInfo? = null,
    // Failure metadata
    val lastFailureType: CompatibilityFailureType? = null,
    val lastFailureReason: String? = null,
    val failureCount: Int = 0,
    val failureLimit: Int = 2,
    val isTerminalFailure: Boolean = false,
    val lastFailureAt: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val totalDurationMs: Long? = null,
    /** Timestamp of the last media-serving signal (HLS playlist/segment request). */
    val lastMediaServedAt: Long? = null,
    /** The priority of this job. */
    val priority: JobPriority = JobPriority.LOW,
) {
    val canServePlayback: Boolean
        get() = status == CompatibilityStatus.PLAYABLE_NOW ||
            status == CompatibilityStatus.READY ||
            hlsReady ||
            directReady

    val isFinalized: Boolean
        get() = status == CompatibilityStatus.READY || preparedAsset?.isComplete == true

    /**
     * The current ideal mode for playback.
     * Prefers PREPARED_MP4 if fully ready, otherwise LIVE_HLS if streamReady,
     * otherwise falls back based on the decision mode.
     */
    val effectivePlaybackMode: EffectivePlaybackMode
        get() = when {
            decision.mode == com.ghoststream.core.model.PlaybackMode.DIRECT -> EffectivePlaybackMode.DIRECT
            directReady || status == CompatibilityStatus.PLAYABLE_NOW || status == CompatibilityStatus.READY -> EffectivePlaybackMode.PREPARED_MP4
            hlsReady || streamable -> EffectivePlaybackMode.LIVE_HLS
            else -> EffectivePlaybackMode.PENDING
        }

    /**
     * Coarse progress rounded to nearest 5% bucket, used for throttled UI updates.
     * Returns null if progressPercent is null.
     */
    val coarseProgressBucket: Int?
        get() = progressPercent?.let { (it / 5) * 5 }

    /** True if this job is considered "large file mode" — long-running or big file. */
    val isLargeFile: Boolean
        get() {
            val elapsed = System.currentTimeMillis() - lastProgressAt
            // If progress hasn't moved more than 10% in 30 seconds, treat as large
            return elapsed > 30_000L && (progressPercent ?: 0) < 90
        }
}

/**
 * Lightweight user-visible session model for UI rendering.
 * Internal state updates frequently; this model is throttled and debounced.
 */
data class UserVisibleSessionState(
    val connectedDeviceCount: Int = 0,
    val shareUrl: String? = null,
    val currentMediaPrepareState: CompatibilityStatus = CompatibilityStatus.IDLE,
    val coarseProgress: Int? = null,
    val isReady: Boolean = false,
    val isFailed: Boolean = false,
    val statusMessage: String? = null,
    val statusMessageResId: Int? = null,
    val statusMessageArgs: List<String> = emptyList(),
)

sealed interface PlaybackSource {
    val mimeType: String?
    val sizeBytes: Long

    data class OriginalUri(
        val uriString: String,
        override val mimeType: String?,
        override val sizeBytes: Long,
    ) : PlaybackSource

    data class CachedFile(
        val filePath: String,
        override val mimeType: String?,
        override val sizeBytes: Long,
        val allowGrowing: Boolean = false,
        val isComplete: Boolean = true,
        val isFragmentedMp4: Boolean = false,
    ) : PlaybackSource
}

sealed interface PlaybackResolution {
    data class Ready(
        val source: PlaybackSource,
        val job: CompatibilityJob,
    ) : PlaybackResolution

    data class Pending(
        val job: CompatibilityJob,
    ) : PlaybackResolution

    data class Failed(
        val job: CompatibilityJob,
    ) : PlaybackResolution
}
