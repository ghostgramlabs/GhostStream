package com.ghoststream.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TransferDirection {
    SENT,
    RECEIVED,
}

@Serializable
enum class TransferStatus {
    COMPLETED,
    FAILED,
    CANCELLED,
}

@Serializable
data class TransferRecord(
    val id: String,
    val name: String,
    val direction: TransferDirection,
    val sizeBytes: Long,
    val timestampMs: Long,
    val peer: String, // IP or device name
    val status: TransferStatus = TransferStatus.COMPLETED,
    val category: MediaCategory = MediaCategory.FILE,
    val fileUri: String? = null, // Path to local file if RECEIVED
)
