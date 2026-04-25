package com.ghoststream.core.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface QuickTextDao {
    @Query("SELECT * FROM quick_text_messages ORDER BY timestampMs DESC")
    fun getAllMessages(): Flow<List<QuickTextMessageEntity>>

    @Query("SELECT * FROM quick_text_messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): QuickTextMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: QuickTextMessageEntity)

    @Query("DELETE FROM quick_text_messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM quick_text_messages")
    suspend fun clearAll()
}
