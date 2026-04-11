package com.ghoststream.core.model

import kotlinx.serialization.Serializable

@Serializable
data class IncomingUploadCompletion(
    val requestId: String,
    val fileName: String,
    val fileCount: Int,
    val totalSizeBytes: Long,
    val requesterIp: String,
    val completedAtEpochMs: Long,
)
