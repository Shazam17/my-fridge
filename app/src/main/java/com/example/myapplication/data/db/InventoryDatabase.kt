package com.example.myapplication.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDate

/**
 * Единый SQLite-файл `filesDir/inventory.db` (SYNC_SPEC.md, раздел 3).
 * Схема версионируется; экспорт схем — в папку `app/schemas`.
 */
@Database(
    entities = [
        SectionEntity::class,
        ProductEntity::class,
        CommentEntity::class,
        SyncStateEntity::class,
        EventEntity::class,
        RemoteSegmentEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class InventoryDatabase : RoomDatabase() {

    abstract fun sectionDao(): SectionDao
    abstract fun productDao(): ProductDao
    abstract fun commentDao(): CommentDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun eventDao(): EventDao
    abstract fun remoteSegmentDao(): RemoteSegmentDao

    companion object {
        const val DB_FILE_NAME = "inventory.db"

        fun create(context: Context): InventoryDatabase =
            Room.databaseBuilder(
                context,
                InventoryDatabase::class.java,
                context.filesDir.resolve(DB_FILE_NAME).absolutePath,
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .addCallback(SystemSectionsSeed)
                .build()
    }
}

/** Предустановленные разделы при первом создании БД (TECHNICAL_SPEC.md, 5.4). */
internal object SystemSectionsSeed : RoomDatabase.Callback() {

    /** uuid фиксированные, чтобы разделы не дублировались при слиянии (SYNC_SPEC.md, раздел 4). */
    private val systemSections = listOf(
        Triple("section-food", "Продукты", "food"),
        Triple("section-chemistry", "Химия", "chemistry"),
        Triple("section-pills", "Таблетки", "pills"),
    )

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        val now = System.currentTimeMillis()
        systemSections.forEachIndexed { index, (uuid, name, iconKey) ->
            db.execSQL(
                """
                INSERT INTO section
                    (uuid, name, iconKey, sortOrder, isHidden, isSystem,
                     createdAt, updatedAt, updatedBy, deleted, deletedAt)
                VALUES (?, ?, ?, ?, 0, 1, ?, ?, 'system', 0, NULL)
                """.trimIndent(),
                arrayOf<Any>(uuid, name, iconKey, index + 1, now, now),
            )
        }
    }
}

class Converters {
    @TypeConverter
    fun localDateToEpochDay(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter
    fun epochDayToLocalDate(epochDay: Long?): LocalDate? = epochDay?.let(LocalDate::ofEpochDay)
}
