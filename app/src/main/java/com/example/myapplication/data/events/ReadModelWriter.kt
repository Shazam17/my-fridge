package com.example.myapplication.data.events

import com.example.myapplication.data.db.CommentEntity
import com.example.myapplication.data.db.InventoryDatabase
import com.example.myapplication.data.db.ProductEntity
import com.example.myapplication.data.db.ProductUnit
import com.example.myapplication.data.db.SectionEntity
import com.example.myapplication.domain.events.ReadModel
import com.example.myapplication.domain.events.SystemSections
import java.time.LocalDate

/**
 * Записывает результат свёртки в таблицы read model (`section`, `product`, `comment`).
 * Вызывать внутри транзакции Room. Строки сопоставляются по `uuid`, локальный `id` сохраняется.
 * Поля, которых нет в свёртке (createdAt, updatedBy), берутся из уже существующей строки;
 * для новой строки ставятся по умолчанию (`remote`, текущее время).
 */
class ReadModelWriter(private val db: InventoryDatabase) {

    suspend fun write(model: ReadModel, now: Long) {
        val sectionDao = db.sectionDao()
        val productDao = db.productDao()
        val commentDao = db.commentDao()

        val sectionIds = mutableMapOf<String, Long>()
        for (s in model.sections.values) {
            val existing = sectionDao.getByUuid(s.uuid)
            val entity = SectionEntity(
                id = existing?.id ?: 0,
                uuid = s.uuid,
                name = s.name,
                iconKey = s.iconKey,
                sortOrder = s.sortOrder.toInt(),
                isHidden = s.hidden,
                isSystem = existing?.isSystem ?: (s.uuid in SystemSections.uuids),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                updatedBy = existing?.updatedBy ?: "remote",
                deleted = s.deleted,
                deletedAt = if (s.deleted) existing?.deletedAt ?: now else null,
            )
            sectionIds[s.uuid] = if (existing == null) sectionDao.insert(entity) else {
                sectionDao.update(entity); existing.id
            }
        }

        val productIds = mutableMapOf<String, Long>()
        for (p in model.products.values) {
            val sectionId = sectionIds[p.sectionUuid] ?: sectionDao.getByUuid(p.sectionUuid)?.id ?: continue
            val existing = productDao.getByUuid(p.uuid)
            val entity = ProductEntity(
                id = existing?.id ?: 0,
                uuid = p.uuid,
                name = p.name,
                sectionId = sectionId,
                iconKey = p.iconKey,
                expiresAt = p.expiresAt?.let { LocalDate.parse(it) },
                quantity = p.quantity?.toInt(),
                unit = p.unit?.let { u -> ProductUnit.entries.firstOrNull { it.name == u } },
                dosage = p.dosage,
                intakeFrequency = p.intakeFrequency,
                isOutOfStock = p.outOfStock,
                isInShoppingList = p.inShoppingList,
                isPriority = p.priority,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                updatedBy = existing?.updatedBy ?: "remote",
                deleted = p.deleted,
                deletedAt = if (p.deleted) existing?.deletedAt ?: now else null,
            )
            productIds[p.uuid] = if (existing == null) productDao.insert(entity) else {
                productDao.update(entity); existing.id
            }
        }

        for (c in model.comments.values) {
            val productId = productIds[c.productUuid] ?: productDao.getByUuid(c.productUuid)?.id ?: continue
            val existing = commentDao.getByUuid(c.uuid)
            val entity = CommentEntity(
                id = existing?.id ?: 0,
                uuid = c.uuid,
                productId = productId,
                text = c.text,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                updatedBy = existing?.updatedBy ?: "remote",
                deleted = c.deleted,
                deletedAt = if (c.deleted) existing?.deletedAt ?: now else null,
            )
            if (existing == null) commentDao.insert(entity) else commentDao.update(entity)
        }
    }
}
