package com.ghoststream.core.media

import com.ghoststream.core.model.PlaybackMode
import com.ghoststream.core.model.SharedItem

sealed interface CompatibilityWorkerResult {
    data class Success(
        val preparedAsset: CachedPlaybackAsset,
        val message: String,
    ) : CompatibilityWorkerResult

    data class Failure(
        val message: String,
    ) : CompatibilityWorkerResult
}

data class CompatibilityWorkerUpdate(
    val status: CompatibilityStatus? = null,
    val message: String? = null,
    val progressPercent: Int? = null,
    val preparedAsset: CachedPlaybackAsset? = null,
    val streamable: Boolean? = null,
)

interface CompatibilityWorker {
    suspend fun prepare(
        item: SharedItem,
        cache: PlaybackCache,
        onUpdate: (CompatibilityWorkerUpdate) -> Unit,
    ): CompatibilityWorkerResult

    fun cancel(itemId: String) {}

    fun cancelAll() {}
}

class StubCompatibilityWorker : CompatibilityWorker {
    override suspend fun prepare(
        item: SharedItem,
        cache: PlaybackCache,
        onUpdate: (CompatibilityWorkerUpdate) -> Unit,
    ): CompatibilityWorkerResult {
        return CompatibilityWorkerResult.Failure(
            message = when (item.playbackDecision.mode) {
                PlaybackMode.REMUX -> "Remux architecture is ready, but container optimization is not enabled in this build yet."
                PlaybackMode.TRANSCODE -> "Transcode architecture is wired, but compatibility conversion is not enabled in this build yet."
                PlaybackMode.DIRECT -> item.playbackDecision.reason
            },
        )
    }
}
