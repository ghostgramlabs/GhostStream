package com.ghoststream.core.media

import kotlinx.serialization.Serializable

@Serializable
enum class SourceType {
    APP_FILE,
    MEDIASTORE_URI,
    SAF_DOCUMENT_URI,
    PROVIDER_URI,
    OTHER_URI
}

@Serializable
sealed class StableWorkerSource {
    @Serializable
    data class AppPrivateFile(val path: String) : StableWorkerSource()
    @Serializable
    data class PersistedUri(val uri: String) : StableWorkerSource()
    @Serializable
    data class MediaStoreUri(val uri: String) : StableWorkerSource()
}

@Serializable
data class StabilizedSourceInfo(
    val originalUri: String,
    val sourceType: SourceType,
    val mimeType: String?,
    val sizeBytes: Long,
    val persistablePermissionAttempted: Boolean = false,
    val persistablePermissionSucceeded: Boolean = false,
    val normalizationAttempted: Boolean = false,
    val normalizationConfidence: Float = 0f,
    val normalizationVerified: Boolean = false,
    val normalizationFailureReason: String? = null,
    val readProbeSuccess: Boolean = false,
    val chosenStableSource: StableWorkerSource? = null,
    val stabilizationTimestamp: Long = System.currentTimeMillis()
)
