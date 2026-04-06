package com.ghoststream.core.history

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ghoststream.core.model.MediaCategory
import com.ghoststream.core.model.TransferDirection
import com.ghoststream.core.model.TransferRecord
import com.ghoststream.core.model.TransferStatus

@Entity(tableName = "transfers")
internal data class TransferEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val direction: TransferDirection,
    val sizeBytes: Long,
    val timestampMs: Long,
    val peer: String,
    val status: TransferStatus,
    val category: MediaCategory,
    val fileUri: String?,
) {
    fun toDomain(): TransferRecord = TransferRecord(
        id = id,
        name = name,
        direction = direction,
        sizeBytes = sizeBytes,
        timestampMs = timestampMs,
        peer = peer,
        status = status,
        category = category,
        fileUri = fileUri,
    )

    companion object {
        fun from(record: TransferRecord): TransferEntity = TransferEntity(
            id = record.id,
            name = record.name,
            direction = record.direction,
            sizeBytes = record.sizeBytes,
            timestampMs = record.timestampMs,
            peer = record.peer,
            status = record.status,
            category = record.category,
            fileUri = record.fileUri,
        )
    }
}
