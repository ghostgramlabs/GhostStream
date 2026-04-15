package com.ghoststream.core.history

import android.content.Context
import androidx.room.*
import com.ghoststream.core.model.MediaCategory
import com.ghoststream.core.model.TransferDirection
import com.ghoststream.core.model.TransferStatus

@Database(entities = [TransferEntity::class], version = 1, exportSchema = false)
@TypeConverters(HistoryConverters::class)
internal abstract class HistoryDatabase : RoomDatabase() {
    abstract val dao: TransferDao

    companion object {
        fun create(context: Context): HistoryDatabase = Room.databaseBuilder(
            context,
            HistoryDatabase::class.java,
            "ghoststream_history.db",
        ).build()
    }
}

internal class HistoryConverters {
    @TypeConverter
    fun toDirection(value: String): TransferDirection = TransferDirection.valueOf(value)
    
    @TypeConverter
    fun fromDirection(value: TransferDirection): String = value.name

    @TypeConverter
    fun toStatus(value: String): TransferStatus = TransferStatus.valueOf(value)
    
    @TypeConverter
    fun fromStatus(value: TransferStatus): String = value.name

    @TypeConverter
    fun toCategory(value: String): MediaCategory = MediaCategory.valueOf(value)
    
    @TypeConverter
    fun fromCategory(value: MediaCategory): String = value.name
}
