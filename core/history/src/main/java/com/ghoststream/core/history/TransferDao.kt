package com.ghoststream.core.history

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
internal interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY timestampMs DESC")
    fun getAllHistory(): Flow<List<TransferEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transfer: TransferEntity)

    @Delete
    suspend fun delete(transfer: TransferEntity)

    @Query("DELETE FROM transfers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM transfers WHERE direction = 'RECEIVED' AND fileUri IS NOT NULL ORDER BY timestampMs DESC")
    fun getReceivedItems(): Flow<List<TransferEntity>>

    @Query("DELETE FROM transfers")
    suspend fun clearAll()
}
