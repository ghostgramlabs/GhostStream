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
    suspend fun clearSent()
    suspend fun clearReceived()
    suspend fun addQuickTextMessage(message: QuickTextMessage)
    suspend fun getQuickTextMessage(id: String): QuickTextMessage?
    suspend fun deleteQuickTextMessage(id: String)
    suspend fun clearQuickTextMessages()
}

class RoomHistoryRepository(context: Context) : HistoryRepository {
    private val db = HistoryDatabase.create(context)
    private val dao = db.dao
    private val quickTextDao = db.quickTextDao
    
    private val recentTransfers = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
        val key = "${record.direction}_${record.name}_${record.peer}"
        val now = System.currentTimeMillis()
        val lastTime = recentTransfers[key]
        if (lastTime != null && (now - lastTime) < 5000L) {
            return
        }
        recentTransfers[key] = now
        dao.insert(TransferEntity.from(record))
    }

    override suspend fun deleteRecord(id: String) {
        dao.deleteById(id)
    }

    override suspend fun clearAll() {
        dao.clearAll()
    }

    override suspend fun clearSent() {
        dao.clearSent()
    }

    override suspend fun clearReceived() {
        dao.clearReceived()
    }

    override suspend fun addQuickTextMessage(message: QuickTextMessage) {
        quickTextDao.insert(QuickTextMessageEntity.from(message))
    }

    override suspend fun getQuickTextMessage(id: String): QuickTextMessage? {
        return quickTextDao.getById(id)?.toDomain()
    }

    override suspend fun deleteQuickTextMessage(id: String) {
        quickTextDao.deleteById(id)
    }

    override suspend fun clearQuickTextMessages() {
        quickTextDao.clearAll()
    }
}
