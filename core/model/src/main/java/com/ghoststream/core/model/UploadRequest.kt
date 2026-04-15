package com.ghoststream.core.model

import kotlinx.serialization.Serializable

@Serializable
data class UploadRequest(
    val id: String,
    val fileName: String,
    val fileCount: Int,
    val sizeBytes: Long,
    val requesterIp: String,
    val requestedAtEpochMs: Long,
)
