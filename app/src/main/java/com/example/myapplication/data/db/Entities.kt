package com.example.myapplication.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * Все синхронизируемые таблицы (SYNC_SPEC.md, раздел 4) содержат:
 * uuid (глобальный id для слияния), updatedAt / updatedBy (LWW), deleted / deletedAt (tombstone).
 * Время хранится как epoch ms.
 */

@Entity(
    tableName = "section",
    indices = [Index(value = ["uuid"], unique = true)],
)
data class SectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val name: String,
    val iconKey: String,
    val sortOrder: Int,
    val isHidden: Boolean = false,
    val isSystem: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val updatedBy: String,
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "product",
    foreignKeys = [
        ForeignKey(
            entity = SectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["sectionId"]),
        Index(value = ["isInShoppingList"]),
        Index(value = ["expiresAt"]),
    ],
)
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val name: String,
    val sectionId: Long,
    val iconKey: String? = null,
    /** Срок годности. null = не указан. */
    val expiresAt: LocalDate? = null,
    /** Количество. null = не учитывается. */
    val quantity: Int? = null,
    val unit: ProductUnit? = null,
    /** Дозировка, например «500 мг». Заполняется в основном для таблеток. */
    val dosage: String? = null,
    /** Периодичность приёма, например «2 раза в день». */
    val intakeFrequency: String? = null,
    val isOutOfStock: Boolean = false,
    val isInShoppingList: Boolean = false,
    /** Пометка «использовать в первую очередь»: такие предметы выводятся выше остальных. */
    @ColumnInfo(defaultValue = "0")
    val isPriority: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val updatedBy: String,
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "comment",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["productId"]),
    ],
)
data class CommentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val productId: Long,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
    val updatedBy: String,
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
)

enum class ProductUnit(val label: String) {
    PIECE("шт."),
    PACK("упаковка"),
    FLASK("флакон"),
    SACHET("пачка"),
    TUBE("тюбик"),
    BOTTLE("бутылка"),
    KILOGRAM("кг"),
    LITER("л"),
}

/**
 * Локальное состояние синхронизации (SYNC_SPEC.md, раздел 3).
 * Таблица одна строка (id = 1) и не синхронизируется.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    /** ETag последней известной удалённой версии. */
    val remoteEtag: String? = null,
    val lastSyncAt: Long? = null,
    /** UUID устройства, генерируется один раз. */
    val deviceId: String,
    /** Состояние HLC (EVENT_SOURCING_SPEC.md, раздел 2.2). */
    val hlcWall: Long = 0,
    val hlcCounter: Int = 0,
    /** Последний использованный seq локального устройства. */
    val lastSeq: Long = 0,
)
