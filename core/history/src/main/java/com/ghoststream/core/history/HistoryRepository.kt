package com.ghoststream.core.history

import android.content.Context
import com.ghoststream.core.model.QuickTextMessage
import com.ghoststream.core.model.TransferRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface HistoryRepository {
    val allHistory: Flow<List<TransferRecord>>
    val receivedItems: Flow<List<TransferRecord>>
    val quickTextMessages: Flow<List<QuickTextMessage>>
    
    suspend fun addRecord(record: TransferRecord)
    suspend fun deleteRecord(id: String)
    suspend fun clearAll()
    suspend fun addQuickTextMessage(message: QuickTextMessage)
    suspend fun deleteQuickTextMessage(id: String)
    suspend fun clearQuickTextMessages()
}

class RoomHistoryRepository(context: Context) : HistoryRepository {
    private val db = HistoryDatabase.create(context)
    private val dao = db.dao
    private val quickTextDao = db.quickTextDao

    override val allHistory: Flow<List<TransferRecord>> = dao.getAllHistory().map { entities ->
        entities.map { it.toDomain() }
    }

    override val receivedItems: Flow<List<TransferRecord>> = dao.getReceivedItems().map { entities ->
        entities.map { it.toDomain() }
    }

    override val quickTextMessages: Flow<List<QuickTextMessage>> = quickTextDao.getAllMessages().map { entities ->
        entities.map { it.toDomain() }
    }

    override suspend fun addRecord(record: TransferRecord) {
        dao.insert(TransferEntity.from(record))
    }

    override suspend fun deleteRecord(id: String) {
        dao.deleteById(id)
    }

    override suspend fun clearAll() {
        dao.clearAll()
    }

    override suspend fun addQuickTextMessage(message: QuickTextMessage) {
        quickTextDao.insert(QuickTextMessageEntity.from(message))
    }

    override suspend fun deleteQuickTextMessage(id: String) {
        quickTextDao.deleteById(id)
    }

    override suspend fun clearQuickTextMessages() {
        quickTextDao.clearAll()
    }
}
