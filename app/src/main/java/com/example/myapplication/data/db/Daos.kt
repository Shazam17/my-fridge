package com.example.myapplication.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Раздел и количество его (неудалённых) предметов. */
data class SectionWithCount(
    @Embedded val section: SectionEntity,
    val productCount: Int,
)

@Dao
interface SectionDao {

    /** Только видимые, неудалённые разделы: главный экран. */
    @Query(
        """
        SELECT s.*, (
            SELECT COUNT(*) FROM product p
            WHERE p.sectionId = s.id AND p.deleted = 0
        ) AS productCount
        FROM section s
        WHERE s.deleted = 0 AND s.isHidden = 0
        ORDER BY s.sortOrder, s.id
        """
    )
    fun observeVisibleSections(): Flow<List<SectionWithCount>>

    @Query("SELECT * FROM section WHERE id = :id AND deleted = 0")
    fun observeById(id: Long): Flow<SectionEntity?>

    /** Имена неудалённых разделов для проверки уникальности (без учёта регистра, в коде). */
    @Query("SELECT name FROM section WHERE deleted = 0 AND id != :exceptId")
    suspend fun activeNamesExcept(exceptId: Long): List<String>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM section")
    suspend fun nextSortOrder(): Int

    @Query("SELECT * FROM section WHERE id = :id")
    suspend fun getById(id: Long): SectionEntity?

    @Query("SELECT * FROM section WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): SectionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(section: SectionEntity): Long

    @Update
    suspend fun update(section: SectionEntity)
}

@Dao
interface ProductDao {

    /** Предметы раздела: сначала «использовать в первую очередь», затем «есть», затем «закончился» (FR-P1, FR-P15). */
    @Query(
        """
        SELECT * FROM product
        WHERE sectionId = :sectionId AND deleted = 0
        ORDER BY isPriority DESC, isOutOfStock, name COLLATE NOCASE
        """
    )
    fun observeBySection(sectionId: Long): Flow<List<ProductEntity>>

    @Query("SELECT * FROM product WHERE id = :id AND deleted = 0")
    fun observeById(id: Long): Flow<ProductEntity?>

    @Query("SELECT * FROM product WHERE id = :id")
    suspend fun getById(id: Long): ProductEntity?

    @Query("SELECT * FROM product WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): ProductEntity?

    @Query("SELECT * FROM product WHERE sectionId = :sectionId AND deleted = 0")
    suspend fun activeBySection(sectionId: Long): List<ProductEntity>

    /** Поиск по названию во всех разделах (FR-P10). */
    @Query(
        """
        SELECT * FROM product
        WHERE deleted = 0 AND name LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY name COLLATE NOCASE
        """
    )
    fun search(query: String): Flow<List<ProductEntity>>

    /** Список покупок (FR-SL1). */
    @Query(
        """
        SELECT * FROM product
        WHERE deleted = 0 AND isInShoppingList = 1
        ORDER BY isPriority DESC, name COLLATE NOCASE
        """
    )
    fun observeShoppingList(): Flow<List<ProductEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(product: ProductEntity): Long

    @Update
    suspend fun update(product: ProductEntity)
}

@Dao
interface CommentDao {

    /** Комментарии предмета, новые сверху (FR-C1). */
    @Query(
        """
        SELECT * FROM comment
        WHERE productId = :productId AND deleted = 0
        ORDER BY createdAt DESC
        """
    )
    fun observeByProduct(productId: Long): Flow<List<CommentEntity>>

    @Query("SELECT * FROM comment WHERE id = :id")
    suspend fun getById(id: Long): CommentEntity?

    @Query("SELECT * FROM comment WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): CommentEntity?

    @Query("SELECT * FROM comment WHERE productId = :productId AND deleted = 0")
    suspend fun activeByProduct(productId: Long): List<CommentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(comment: CommentEntity): Long

    @Update
    suspend fun update(comment: CommentEntity)
}

@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun get(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)
}
