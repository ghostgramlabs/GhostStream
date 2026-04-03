package com.ghoststream.core.media

import com.ghoststream.core.model.PlaybackDecision
import kotlinx.serialization.Serializable

@Serializable
enum class CompatibilityStatus {
    IDLE,
    QUEUED,
    PREPARING,
    READY,
    FAILED,
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
)

@Serializable
data class CompatibilityJob(
    val itemId: String,
    val decision: PlaybackDecision,
    val status: CompatibilityStatus = CompatibilityStatus.IDLE,
    val message: String = decision.reason,
    val progressPercent: Int? = null,
    val preparedAsset: CachedPlaybackAsset? = null,
    val streamable: Boolean = status == CompatibilityStatus.READY,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
) {
    val canServePlayback: Boolean
        get() = status == CompatibilityStatus.READY || (streamable && preparedAsset != null)
}

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
