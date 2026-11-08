package com.example.myapplication.data

import androidx.room.withTransaction
import com.example.myapplication.data.db.CommentDao
import com.example.myapplication.data.db.CommentEntity
import com.example.myapplication.data.db.InventoryDatabase
import com.example.myapplication.data.db.ProductDao
import com.example.myapplication.data.db.ProductEntity
import com.example.myapplication.data.db.ProductUnit
import com.example.myapplication.data.events.EventRecorder
import com.example.myapplication.domain.ProductInput
import com.example.myapplication.domain.ProductValidation
import com.example.myapplication.domain.StockChange
import com.example.myapplication.domain.adjustQuantity
import com.example.myapplication.domain.events.EventType
import com.example.myapplication.domain.outOfStockAfterSave
import com.example.myapplication.domain.toggleOutOfStock
import com.example.myapplication.domain.validateComment
import com.example.myapplication.domain.validateProduct
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Работа с предметами и комментариями.
 * Удаление мягкое: deleted = true + deletedAt (tombstone, SYNC_SPEC.md, раздел 5).
 * Каждая команда пишет событие в той же транзакции, что и строку (EVENT_SOURCING_SPEC.md, раздел 5).
 */
class ProductRepository(
    private val db: InventoryDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val author: String = LOCAL_AUTHOR,
) {
    private val productDao: ProductDao = db.productDao()
    private val commentDao: CommentDao = db.commentDao()
    private val eventRecorder = EventRecorder(
        events = db.eventDao(),
        syncState = db.syncStateDao(),
        author = author,
        now = clock,
    )

    fun observeBySection(sectionId: Long): Flow<List<ProductEntity>> =
        productDao.observeBySection(sectionId)

    fun observeProduct(id: Long): Flow<ProductEntity?> = productDao.observeById(id)

    suspend fun getProduct(id: Long): ProductEntity? = productDao.getById(id)

    fun search(query: String): Flow<List<ProductEntity>> =
        productDao.search(query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"))

    fun observeShoppingList(): Flow<List<ProductEntity>> = productDao.observeShoppingList()

    /**
     * Сохраняет новый предмет или изменения существующего. Возвращает ошибки формы.
     * Новый предмет → ProductCreated со всеми полями. Редактирование → ProductUpdated
     * только с изменившимися полями (частичное обновление, EVENT_SOURCING_SPEC.md, раздел 3.1).
     */
    suspend fun saveProduct(
        id: Long?,
        sectionId: Long,
        input: ProductInput,
        iconKey: String? = null,
    ): ProductValidation = db.withTransaction {
        val validation = validateProduct(input)
        val value = validation.value ?: return@withTransaction validation
        val now = clock()
        val existing = id?.let { productDao.getById(it) }
        if (existing == null) {
            val uuid = UUID.randomUUID().toString()
            val outOfStock = outOfStockAfterSave(value.quantity, manualFlag = false)
            productDao.insert(
                ProductEntity(
                    uuid = uuid,
                    name = value.name,
                    sectionId = sectionId,
                    iconKey = iconKey,
                    expiresAt = value.expiresAt,
                    quantity = value.quantity,
                    unit = value.unit,
                    dosage = value.dosage,
                    intakeFrequency = value.frequency,
                    isOutOfStock = outOfStock,
                    createdAt = now,
                    updatedAt = now,
                    updatedBy = author,
                ),
            )
            val sectionUuid = sectionUuidOf(sectionId)
            eventRecorder.record(
                EventType.PRODUCT_CREATED,
                uuid,
                mapOf(
                    "name" to value.name,
                    "sectionId" to sectionUuid,
                    "iconKey" to iconKey,
                    "expiresAt" to value.expiresAt?.toString(),
                    "quantity" to value.quantity?.toLong(),
                    "unit" to value.unit?.name,
                    "dosage" to value.dosage,
                    "intakeFrequency" to value.frequency,
                ),
            )
        } else {
            val newIcon = iconKey ?: existing.iconKey
            val newOutOfStock = outOfStockAfterSave(value.quantity, existing.isOutOfStock)
            productDao.update(
                existing.copy(
                    name = value.name,
                    sectionId = sectionId,
                    iconKey = newIcon,
                    expiresAt = value.expiresAt,
                    quantity = value.quantity,
                    unit = value.unit,
                    dosage = value.dosage,
                    intakeFrequency = value.frequency,
                    isOutOfStock = newOutOfStock,
                    updatedAt = now,
                    updatedBy = author,
                ),
            )
            val changes = buildMap<String, Any?> {
                if (value.name != existing.name) put("name", value.name)
                if (newIcon != existing.iconKey) put("iconKey", newIcon)
                if (value.expiresAt != existing.expiresAt) put("expiresAt", value.expiresAt?.toString())
                if (value.quantity != existing.quantity) put("quantity", value.quantity?.toLong())
                if (value.unit != existing.unit) put("unit", value.unit?.name)
                if (value.dosage != existing.dosage) put("dosage", value.dosage)
                if (value.frequency != existing.intakeFrequency) put("intakeFrequency", value.frequency)
            }
            if (changes.isNotEmpty()) {
                eventRecorder.record(EventType.PRODUCT_UPDATED, existing.uuid, changes)
            }
            if (sectionId != existing.sectionId) {
                eventRecorder.record(
                    EventType.PRODUCT_MOVED,
                    existing.uuid,
                    mapOf("sectionId" to sectionUuidOf(sectionId)),
                )
            }
            if (newOutOfStock != existing.isOutOfStock) {
                eventRecorder.record(
                    EventType.PRODUCT_OUT_OF_STOCK_SET,
                    existing.uuid,
                    mapOf("outOfStock" to newOutOfStock),
                )
            }
        }
        validation
    }

    /** Пометка «закончился» / снятие одним действием (FR-P6). */
    suspend fun toggleOutOfStockFlag(id: Long) = updateStock(id) { quantity, isOut ->
        toggleOutOfStock(quantity, isOut)
    }

    /**
     * Кнопки «+» / «−» (FR-P13). Пишется ДЕЛЬТА (EVENT_SOURCING_SPEC.md, раздел 1):
     * одновременные правки на двух устройствах складываются, а не перетирают друг друга.
     */
    suspend fun adjustStock(id: Long, delta: Int) = db.withTransaction {
        val product = productDao.getById(id) ?: return@withTransaction
        val result = adjustQuantity(product.quantity ?: 0, delta)
        productDao.update(
            product.copy(
                quantity = result.quantity,
                unit = product.unit ?: ProductUnit.PIECE,
                isOutOfStock = result.isOutOfStock,
                updatedAt = clock(),
                updatedBy = author,
            ),
        )
        eventRecorder.record(
            EventType.PRODUCT_QUANTITY_ADJUSTED,
            product.uuid,
            mapOf("delta" to delta.toLong()),
        )
    }

    private suspend fun updateStock(
        id: Long,
        change: (Int?, Boolean) -> StockChange,
    ) = db.withTransaction {
        val product = productDao.getById(id) ?: return@withTransaction
        val result = change(product.quantity, product.isOutOfStock)
        productDao.update(
            product.copy(
                quantity = result.quantity,
                unit = product.unit ?: if (result.quantity != null) ProductUnit.PIECE else null,
                isOutOfStock = result.isOutOfStock,
                updatedAt = clock(),
                updatedBy = author,
            ),
        )
        eventRecorder.record(
            EventType.PRODUCT_OUT_OF_STOCK_SET,
            product.uuid,
            mapOf("outOfStock" to result.isOutOfStock),
        )
        if (result.quantity != product.quantity) {
            eventRecorder.record(
                EventType.PRODUCT_QUANTITY_SET,
                product.uuid,
                mapOf("quantity" to result.quantity?.toLong()),
            )
        }
    }

    /** Добавление/удаление из списка покупок (FR-P7). */
    suspend fun setInShoppingList(id: Long, value: Boolean) = db.withTransaction {
        val product = productDao.getById(id) ?: return@withTransaction
        productDao.update(product.copy(isInShoppingList = value, updatedAt = clock(), updatedBy = author))
        eventRecorder.record(
            EventType.PRODUCT_SHOPPING_LIST_SET,
            product.uuid,
            mapOf("inList" to value),
        )
    }

    /** Пометка «использовать в первую очередь» (FR-P15). */
    suspend fun setPriority(id: Long, value: Boolean) = db.withTransaction {
        val product = productDao.getById(id) ?: return@withTransaction
        if (product.isPriority == value) return@withTransaction
        productDao.update(product.copy(isPriority = value, updatedAt = clock(), updatedBy = author))
        eventRecorder.record(
            EventType.PRODUCT_PRIORITY_SET,
            product.uuid,
            mapOf("priority" to value),
        )
    }

    /**
     * Массовые действия (FR-P16). Всё в одной транзакции; событие пишется только для предметов,
     * которые реально изменились. Уже подходящие предметы пропускаются.
     */
    suspend fun bulkMarkOutOfStock(ids: Collection<Long>) = bulkUpdate(ids, { !it.isOutOfStock }) { product ->
        // Та же семантика, что у FR-P6: при заданном количестве пометка ставит 0.
        val result = toggleOutOfStock(product.quantity, isOutOfStock = false)
        productDao.update(
            product.copy(
                quantity = result.quantity,
                unit = product.unit ?: if (result.quantity != null) ProductUnit.PIECE else null,
                isOutOfStock = true,
                updatedAt = clock(),
                updatedBy = author,
            ),
        )
        eventRecorder.record(EventType.PRODUCT_OUT_OF_STOCK_SET, product.uuid, mapOf("outOfStock" to true))
        if (result.quantity != product.quantity) {
            eventRecorder.record(
                EventType.PRODUCT_QUANTITY_SET,
                product.uuid,
                mapOf("quantity" to result.quantity?.toLong()),
            )
        }
    }

    suspend fun bulkSetPriority(ids: Collection<Long>) = bulkUpdate(ids, { !it.isPriority }) { product ->
        productDao.update(product.copy(isPriority = true, updatedAt = clock(), updatedBy = author))
        eventRecorder.record(EventType.PRODUCT_PRIORITY_SET, product.uuid, mapOf("priority" to true))
    }

    suspend fun bulkAddToShoppingList(ids: Collection<Long>) = bulkUpdate(ids, { !it.isInShoppingList }) { product ->
        productDao.update(product.copy(isInShoppingList = true, updatedAt = clock(), updatedBy = author))
        eventRecorder.record(EventType.PRODUCT_SHOPPING_LIST_SET, product.uuid, mapOf("inList" to true))
    }

    private suspend fun bulkUpdate(
        ids: Collection<Long>,
        needsChange: (ProductEntity) -> Boolean,
        change: suspend (ProductEntity) -> Unit,
    ) = db.withTransaction {
        ids.forEach { id ->
            productDao.getById(id)?.takeIf { !it.deleted && needsChange(it) }?.let { change(it) }
        }
    }

    /** «Куплено» (FR-SL2): убрать из списка и снять «закончился». */
    suspend fun markBought(id: Long) = db.withTransaction {
        val product = productDao.getById(id) ?: return@withTransaction
        val restored = product.quantity?.let { if (it == 0) 1 else it }
        productDao.update(
            product.copy(
                isInShoppingList = false,
                isOutOfStock = false,
                quantity = restored,
                updatedAt = clock(),
                updatedBy = author,
            ),
        )
        eventRecorder.record(EventType.PRODUCT_BOUGHT, product.uuid, emptyMap())
    }

    /** Перенос в другой раздел (FR-P4). Комментарии и флаги сохраняются. */
    suspend fun moveToSection(id: Long, sectionId: Long) = db.withTransaction {
        val product = productDao.getById(id) ?: return@withTransaction
        productDao.update(product.copy(sectionId = sectionId, updatedAt = clock(), updatedBy = author))
        eventRecorder.record(
            EventType.PRODUCT_MOVED,
            product.uuid,
            mapOf("sectionId" to sectionUuidOf(sectionId)),
        )
    }

    /**
     * Удаление предмета вместе с комментариями (FR-P8). Одно событие ProductDeleted:
     * каскад на комментарии выполняет свёртка. Строки комментариев помечаются здесь же.
     */
    suspend fun deleteProduct(id: Long) = db.withTransaction {
        val now = clock()
        productDao.getById(id)?.let { product ->
            productDao.update(product.copy(deleted = true, deletedAt = now, updatedAt = now, updatedBy = author))
            commentDao.activeByProduct(id).forEach { c ->
                commentDao.update(c.copy(deleted = true, deletedAt = now, updatedAt = now, updatedBy = author))
            }
            eventRecorder.record(EventType.PRODUCT_DELETED, product.uuid, emptyMap())
        }
    }

    fun observeComments(productId: Long): Flow<List<CommentEntity>> =
        commentDao.observeByProduct(productId)

    suspend fun addComment(productId: Long, text: String): Boolean = db.withTransaction {
        if (!validateComment(text)) return@withTransaction false
        val now = clock()
        val uuid = UUID.randomUUID().toString()
        val trimmed = text.trim()
        commentDao.insert(
            CommentEntity(
                uuid = uuid,
                productId = productId,
                text = trimmed,
                createdAt = now,
                updatedAt = now,
                updatedBy = author,
            ),
        )
        val productUuid = productDao.getById(productId)?.uuid ?: return@withTransaction true
        eventRecorder.record(
            EventType.COMMENT_ADDED,
            uuid,
            mapOf("productId" to productUuid, "text" to trimmed),
        )
        true
    }

    suspend fun editComment(id: Long, text: String): Boolean = db.withTransaction {
        if (!validateComment(text)) return@withTransaction false
        val comment = commentDao.getById(id) ?: return@withTransaction false
        val trimmed = text.trim()
        commentDao.update(comment.copy(text = trimmed, updatedAt = clock(), updatedBy = author))
        eventRecorder.record(EventType.COMMENT_EDITED, comment.uuid, mapOf("text" to trimmed))
        true
    }

    suspend fun deleteComment(id: Long) = db.withTransaction {
        val comment = commentDao.getById(id) ?: return@withTransaction
        val now = clock()
        commentDao.update(comment.copy(deleted = true, deletedAt = now, updatedAt = now, updatedBy = author))
        eventRecorder.record(EventType.COMMENT_DELETED, comment.uuid, emptyMap())
    }

    /** uuid раздела по локальному id; события ссылаются на родителя по uuid. */
    private suspend fun sectionUuidOf(sectionId: Long): String =
        db.sectionDao().getById(sectionId)?.uuid
            ?: error("Раздел $sectionId не найден")
}
