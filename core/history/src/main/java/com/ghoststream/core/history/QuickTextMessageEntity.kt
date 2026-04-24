package com.ghoststream.core.history

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ghoststream.core.model.QuickTextMessage
import com.ghoststream.core.model.QuickTextTargetType

@Entity(tableName = "quick_text_messages")
internal data class QuickTextMessageEntity(
    @PrimaryKey
    val id: String,
    val text: String,
    val senderId: String,
    val senderName: String,
    val targetType: QuickTextTargetType,
    val targetId: String?,
    val targetName: String?,
    val timestampMs: Long,
) {
    fun toDomain(): QuickTextMessage = QuickTextMessage(
        id = id,
        text = text,
        senderId = senderId,
        senderName = senderName,
        targetType = targetType,
        targetId = targetId,
        targetName = targetName,
        timestampMs = timestampMs,
    )

    companion object {
        fun from(message: QuickTextMessage): QuickTextMessageEntity = QuickTextMessageEntity(
            id = message.id,
            text = message.text,
            senderId = message.senderId,
            senderName = message.senderName,
            targetType = message.targetType,
            targetId = message.targetId,
            targetName = message.targetName,
            timestampMs = message.timestampMs,
        )
    }
}
