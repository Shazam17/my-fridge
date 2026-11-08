package com.example.myapplication.data

import androidx.room.withTransaction
import com.example.myapplication.data.db.InventoryDatabase
import com.example.myapplication.data.db.SectionEntity
import com.example.myapplication.data.db.SectionWithCount
import com.example.myapplication.data.events.EventRecorder
import com.example.myapplication.domain.SectionNameError
import com.example.myapplication.domain.events.EventType
import com.example.myapplication.domain.validateSectionName
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Автор изменений, пока нет экрана подключения с username (SYNC_SPEC.md, раздел 2). */
const val LOCAL_AUTHOR = "device"

/**
 * Команды разделов. Каждая команда пишет событие в той же транзакции, что и строку
 * (EVENT_SOURCING_SPEC.md, раздел 5).
 */
class SectionRepository(
    private val db: InventoryDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val author: String = LOCAL_AUTHOR,
) {
    private val sectionDao = db.sectionDao()
    private val eventRecorder = EventRecorder(
        events = db.eventDao(),
        syncState = db.syncStateDao(),
        author = author,
        now = clock,
    )

    fun observeVisibleSections(): Flow<List<SectionWithCount>> =
        sectionDao.observeVisibleSections()

    fun observeSection(id: Long): Flow<SectionEntity?> = sectionDao.observeById(id)

    /** Создание раздела. Возвращает ошибку валидации или null при успехе. */
    suspend fun createSection(rawName: String, iconKey: String): SectionNameError? =
        db.withTransaction {
            val error = validateSectionName(rawName, sectionDao.activeNamesExcept(-1))
            if (error == null) {
                val now = clock()
                val uuid = UUID.randomUUID().toString()
                val name = rawName.trim()
                val sortOrder = sectionDao.nextSortOrder()
                sectionDao.insert(
                    SectionEntity(
                        uuid = uuid,
                        name = name,
                        iconKey = iconKey,
                        sortOrder = sortOrder,
                        createdAt = now,
                        updatedAt = now,
                        updatedBy = author,
                    ),
                )
                eventRecorder.record(
                    EventType.SECTION_CREATED,
                    uuid,
                    mapOf("name" to name, "iconKey" to iconKey, "sortOrder" to sortOrder.toLong()),
                )
            }
            error
        }

    /** Переименование (TECHNICAL_SPEC.md, раздел 6). */
    suspend fun renameSection(id: Long, rawName: String): SectionNameError? =
        db.withTransaction {
            val error = validateSectionName(rawName, sectionDao.activeNamesExcept(id))
            if (error == null) {
                sectionDao.getById(id)?.let {
                    val name = rawName.trim()
                    sectionDao.update(it.copy(name = name, updatedAt = clock(), updatedBy = author))
                    eventRecorder.record(EventType.SECTION_RENAMED, it.uuid, mapOf("name" to name))
                }
            }
            error
        }

    /** Скрытие раздела: данные остаются, раздел не показывается на главном экране. */
    suspend fun setHidden(id: Long, hidden: Boolean) = db.withTransaction {
        sectionDao.getById(id)?.let {
            sectionDao.update(it.copy(isHidden = hidden, updatedAt = clock(), updatedBy = author))
            eventRecorder.record(EventType.SECTION_HIDDEN, it.uuid, mapOf("hidden" to hidden))
        }
    }

    /**
     * Мягкое удаление раздела. Записывается ОДНО событие SectionDeleted: каскад на предметы
     * и комментарии выполняет свёртка (EVENT_SOURCING_SPEC.md, раздел 6.2).
     * Строки предметов и комментариев помечаются здесь же, чтобы read model совпадал со свёрткой.
     */
    suspend fun deleteSection(id: Long) = db.withTransaction {
        val now = clock()
        sectionDao.getById(id)?.let { section ->
            sectionDao.update(section.copy(deleted = true, deletedAt = now, updatedAt = now, updatedBy = author))
            db.productDao().activeBySection(id).forEach { product ->
                db.productDao().update(product.copy(deleted = true, deletedAt = now, updatedAt = now, updatedBy = author))
                db.commentDao().activeByProduct(product.id).forEach { c ->
                    db.commentDao().update(c.copy(deleted = true, deletedAt = now, updatedAt = now, updatedBy = author))
                }
            }
            eventRecorder.record(EventType.SECTION_DELETED, section.uuid, emptyMap())
        }
    }
}
