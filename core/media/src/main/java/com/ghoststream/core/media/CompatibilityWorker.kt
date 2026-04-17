package com.ghoststream.core.media

import com.ghoststream.core.model.PlaybackDecision
import com.ghoststream.core.model.PlaybackMode
import com.ghoststream.core.model.SharedItem
import kotlinx.serialization.Serializable

@Serializable
enum class CompatibilityFailureType {
    VALIDATION,  // Deterministic output mismatch
    EXPORT,      // Hardware encoder crash / Media3 error
    STALL,       // Timeout
    SOURCE,      // SecurityException / File missing
    CANCEL,      // Explicitly canceled
    UNKNOWN
}

sealed interface CompatibilityWorkerResult {
    data class Success(
        val preparedAsset: CachedPlaybackAsset,
        val message: String,
    ) : CompatibilityWorkerResult

    data class Failure(
        val message: String,
        val type: CompatibilityFailureType = CompatibilityFailureType.UNKNOWN,
    ) : CompatibilityWorkerResult
}

data class CompatibilityWorkerUpdate(
    val decision: PlaybackDecision? = null,
    val status: CompatibilityStatus? = null,
    val message: String? = null,
    val progressPercent: Int? = null,
    val preparedAsset: CachedPlaybackAsset? = null,
    val hlsReady: Boolean? = null,
    val directReady: Boolean? = null,
    val streamable: Boolean? = null,
    val failureType: CompatibilityFailureType? = null,
)

interface CompatibilityWorker {
    suspend fun prepare(
        item: SharedItem,
        cache: PlaybackCache,
        stabilizedSource: StabilizedSourceInfo? = null,
        startOffsetMs: Long = 0L,
        onUpdate: (CompatibilityWorkerUpdate) -> Unit,
    ): CompatibilityWorkerResult

    fun cancel(itemId: String) {}

    fun cancelAll() {}
}

class StubCompatibilityWorker : CompatibilityWorker {
    override suspend fun prepare(
        item: SharedItem,
        cache: PlaybackCache,
        stabilizedSource: StabilizedSourceInfo?,
        startOffsetMs: Long,
        onUpdate: (CompatibilityWorkerUpdate) -> Unit,
    ): CompatibilityWorkerResult {
        return CompatibilityWorkerResult.Failure(
            message = when (item.playbackDecision.mode) {
                PlaybackMode.REMUX -> "Remux architecture is ready, but container optimization is not enabled in this build yet."
                PlaybackMode.TRANSMUX -> "Transmux architecture is ready, but HLS packaging is not enabled in this build yet."
                PlaybackMode.TRANSCODE -> "Transcode architecture is wired, but compatibility conversion is not enabled in this build yet."
                PlaybackMode.DIRECT -> item.playbackDecision.reason
            },
        )
    }
}
