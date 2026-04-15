package com.ghoststream.core.history

import android.content.Context
import com.ghoststream.core.model.TransferRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface HistoryRepository {
    val allHistory: Flow<List<TransferRecord>>
    val receivedItems: Flow<List<TransferRecord>>
    
    suspend fun addRecord(record: TransferRecord)
    suspend fun deleteRecord(id: String)
    suspend fun clearAll()
}

class RoomHistoryRepository(context: Context) : HistoryRepository {
    private val db = HistoryDatabase.create(context)
    private val dao = db.dao

    override val allHistory: Flow<List<TransferRecord>> = dao.getAllHistory().map { entities ->
        entities.map { it.toDomain() }
    }

    override val receivedItems: Flow<List<TransferRecord>> = dao.getReceivedItems().map { entities ->
        entities.map { it.toDomain() }
    }

    override suspend fun addRecord(record: TransferRecord) {
        dao.insert(TransferEntity.from(record))
    }

    override suspend fun deleteRecord(id: String) {
        dao.deleteById(id)
    }

    override suspend fun clearAll() {
        // Not implemented in DAO yet, but could be added
    }
}
