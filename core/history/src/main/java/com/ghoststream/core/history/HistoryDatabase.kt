package com.ghoststream.core.history

import android.content.Context
import androidx.room.*
import com.ghoststream.core.model.MediaCategory
import com.ghoststream.core.model.QuickTextTargetType
import com.ghoststream.core.model.TransferDirection
import com.ghoststream.core.model.TransferStatus

@Database(
    entities = [TransferEntity::class, QuickTextMessageEntity::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(HistoryConverters::class)
internal abstract class HistoryDatabase : RoomDatabase() {
    abstract val dao: TransferDao
    abstract val quickTextDao: QuickTextDao

    companion object {
        fun create(context: Context): HistoryDatabase = Room.databaseBuilder(
            context,
            HistoryDatabase::class.java,
            "ghoststream_history.db",
        ).addMigrations(MIGRATION_1_2).build()

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `quick_text_messages` (
                        `id` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `senderId` TEXT NOT NULL,
                        `senderName` TEXT NOT NULL,
                        `targetType` TEXT NOT NULL,
                        `targetId` TEXT,
                        `targetName` TEXT,
                        `timestampMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }
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

    @TypeConverter
    fun toQuickTextTargetType(value: String): QuickTextTargetType = QuickTextTargetType.valueOf(value)

    @TypeConverter
    fun fromQuickTextTargetType(value: QuickTextTargetType): String = value.name
}
