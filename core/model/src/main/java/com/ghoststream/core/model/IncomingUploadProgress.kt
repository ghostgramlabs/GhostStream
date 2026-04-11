package com.ghoststream.core.model

import kotlinx.serialization.Serializable

@Serializable
data class IncomingUploadProgress(
    val requestId: String,
    val currentFileName: String,
    val fileCount: Int,
    val currentFileIndex: Int,
    val totalSizeBytes: Long,
    val transferredBytes: Long,
    val requesterIp: String,
    val startedAtEpochMs: Long,
) {
    val progressPercent: Int?
        get() = if (totalSizeBytes > 0L) {
            ((transferredBytes.coerceIn(0L, totalSizeBytes) * 100L) / totalSizeBytes).toInt()
        } else {
            null
        }
}
