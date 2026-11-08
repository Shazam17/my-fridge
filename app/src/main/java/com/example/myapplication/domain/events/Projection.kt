package com.example.myapplication.domain.events

import com.example.myapplication.domain.QUANTITY_MAX

/**
 * Состояние read model в памяти: используется свёрткой и тестами.
 * Room-таблицы заполняются из этого же состояния (EVENT_SOURCING_SPEC.md, раздел 5).
 */
data class ProductState(
    val uuid: String,
    val sectionUuid: String,
    val name: String,
    val iconKey: String?,
    /** Дата в ISO-формате (yyyy-MM-dd) или null. */
    val expiresAt: String?,
    val quantity: Long?,
    val unit: String?,
    val dosage: String?,
    val intakeFrequency: String?,
    val outOfStock: Boolean,
    val inShoppingList: Boolean,
    val deleted: Boolean,
    /** Пометка «использовать в первую очередь». */
    val priority: Boolean = false,
)

data class SectionState(
    val uuid: String,
    val name: String,
    val iconKey: String,
    val sortOrder: Long,
    val hidden: Boolean,
    val deleted: Boolean,
)

data class CommentState(
    val uuid: String,
    val productUuid: String,
    val text: String,
    val deleted: Boolean,
)

/** Итоговое состояние после свёртки. */
data class ReadModel(
    val sections: Map<String, SectionState> = emptyMap(),
    val products: Map<String, ProductState> = emptyMap(),
    val comments: Map<String, CommentState> = emptyMap(),
    /** События, которые не удалось применить (EVENT_SOURCING_SPEC.md, раздел 6.3). */
    val rejected: List<RejectedEvent> = emptyList(),
)

data class RejectedEvent(val eventId: String, val reason: String)

/**
 * Свёртка журнала (EVENT_SOURCING_SPEC.md, раздел 6).
 * Вход сортируется по HLC и дедуплицируется по eventId, поэтому результат не зависит от порядка приёма.
 */
object Projection {

    fun fold(events: Collection<DomainEvent>): ReadModel {
        val ordered = events
            .distinctBy { it.eventId }
            .sortedBy { it.hlc }
        val start = ReadModel(sections = SystemSections.all.associateBy { it.uuid })
        return ordered.fold(start) { model, event -> apply(model, event) }
    }

    private fun apply(model: ReadModel, e: DomainEvent): ReadModel = when (e.type) {
        EventType.SECTION_CREATED -> {
            if (e.aggregateId in model.sections) model
            else model.copy(
                sections = model.sections + (e.aggregateId to SectionState(
                    uuid = e.aggregateId,
                    name = e.str("name"),
                    iconKey = e.str("iconKey"),
                    sortOrder = e.long("sortOrder"),
                    hidden = false,
                    deleted = false,
                )),
            )
        }
        EventType.SECTION_RENAMED -> model.updateSection(e) { it.copy(name = e.str("name")) }
        EventType.SECTION_ICON_CHANGED -> model.updateSection(e) { it.copy(iconKey = e.str("iconKey")) }
        EventType.SECTION_HIDDEN -> model.updateSection(e) { it.copy(hidden = e.bool("hidden")) }
        EventType.SECTION_ORDER_CHANGED -> model.updateSection(e) { it.copy(sortOrder = e.long("sortOrder")) }
        EventType.SECTION_DELETED -> deleteSection(model, e)

        EventType.PRODUCT_CREATED -> createProduct(model, e)
        EventType.PRODUCT_UPDATED -> model.updateProduct(e) { p -> p.applyFields(e.payload) }
        EventType.PRODUCT_MOVED -> moveProduct(model, e)
        EventType.PRODUCT_QUANTITY_ADJUSTED -> model.updateProduct(e) { p ->
            val newQty = ((p.quantity ?: 0L) + e.long("delta")).coerceIn(0L, QUANTITY_MAX.toLong())
            p.copy(quantity = newQty, outOfStock = newQty == 0L)
        }
        EventType.PRODUCT_QUANTITY_SET -> model.updateProduct(e) { p ->
            p.copy(quantity = e.optLong("quantity"))
        }
        EventType.PRODUCT_OUT_OF_STOCK_SET -> model.updateProduct(e) { it.copy(outOfStock = e.bool("outOfStock")) }
        EventType.PRODUCT_SHOPPING_LIST_SET -> model.updateProduct(e) { it.copy(inShoppingList = e.bool("inList")) }
        EventType.PRODUCT_PRIORITY_SET -> model.updateProduct(e) { it.copy(priority = e.bool("priority")) }
        EventType.PRODUCT_BOUGHT -> model.updateProduct(e) { p ->
            // FR-SL2: убрать из списка, снять «закончился», остаток 0 → 1.
            // null (количество не учитывается) остаётся null, как в ProductRepository.markBought.
            val qty = p.quantity?.let { if (it == 0L) 1L else it }
            p.copy(inShoppingList = false, outOfStock = false, quantity = qty)
        }
        EventType.PRODUCT_DELETED -> deleteProduct(model, e)

        EventType.COMMENT_ADDED -> {
            val productUuid = e.str("productId")
            val parent = model.products[productUuid]
            when {
                parent == null || parent.deleted -> model.reject(e, "предмет не найден или удалён")
                e.aggregateId in model.comments -> model
                else -> model.copy(
                    comments = model.comments + (e.aggregateId to CommentState(
                        uuid = e.aggregateId,
                        productUuid = productUuid,
                        text = e.str("text"),
                        deleted = false,
                    )),
                )
            }
        }
        EventType.COMMENT_EDITED -> model.updateComment(e) { it.copy(text = e.str("text")) }
        EventType.COMMENT_DELETED -> model.updateComment(e) { it.copy(deleted = true) }
    }

    /** Частичное обновление: применяются только ключи, присутствующие в payload. */
    private fun ProductState.applyFields(payload: Map<String, Any?>): ProductState {
        var p = this
        if (payload.containsKey("name")) p = p.copy(name = payload["name"] as String)
        if (payload.containsKey("iconKey")) p = p.copy(iconKey = payload["iconKey"] as String?)
        if (payload.containsKey("expiresAt")) p = p.copy(expiresAt = payload["expiresAt"] as String?)
        if (payload.containsKey("quantity")) p = p.copy(quantity = (payload["quantity"] as Number?)?.toLong())
        if (payload.containsKey("unit")) p = p.copy(unit = payload["unit"] as String?)
        if (payload.containsKey("dosage")) p = p.copy(dosage = payload["dosage"] as String?)
        if (payload.containsKey("intakeFrequency")) p = p.copy(intakeFrequency = payload["intakeFrequency"] as String?)
        return p
    }

    private fun createProduct(model: ReadModel, e: DomainEvent): ReadModel {
        if (e.aggregateId in model.products) return model
        val sectionUuid = e.str("sectionId")
        val section = model.sections[sectionUuid]
        if (section == null || section.deleted) return model.reject(e, "раздел не найден или удалён")
        val base = ProductState(
            uuid = e.aggregateId,
            sectionUuid = sectionUuid,
            name = e.str("name"),
            iconKey = null,
            expiresAt = null,
            quantity = null,
            unit = null,
            dosage = null,
            intakeFrequency = null,
            outOfStock = false,
            inShoppingList = false,
            deleted = false,
            priority = false,
        ).applyFields(e.payload)
        return model.copy(
            products = model.products + (e.aggregateId to base.copy(outOfStock = base.quantity == 0L)),
        )
    }

    private fun moveProduct(model: ReadModel, e: DomainEvent): ReadModel {
        val target = e.str("sectionId")
        val section = model.sections[target]
        if (section == null || section.deleted) return model.reject(e, "раздел назначения удалён")
        return model.updateProduct(e) { it.copy(sectionUuid = target) }
    }

    private fun deleteSection(model: ReadModel, e: DomainEvent): ReadModel {
        val section = model.sections[e.aggregateId] ?: return model
        if (section.deleted) return model
        val products = model.products.mapValues { (_, p) ->
            if (p.sectionUuid == e.aggregateId && !p.deleted) p.copy(deleted = true) else p
        }
        val deletedProducts = products.values.filter { it.sectionUuid == e.aggregateId }.map { it.uuid }.toSet()
        val comments = model.comments.mapValues { (_, c) ->
            if (c.productUuid in deletedProducts) c.copy(deleted = true) else c
        }
        return model.copy(
            sections = model.sections + (e.aggregateId to section.copy(deleted = true)),
            products = products,
            comments = comments,
        )
    }

    private fun deleteProduct(model: ReadModel, e: DomainEvent): ReadModel {
        val product = model.products[e.aggregateId] ?: return model
        val comments = model.comments.mapValues { (_, c) ->
            if (c.productUuid == e.aggregateId) c.copy(deleted = true) else c
        }
        return model.copy(
            products = model.products + (e.aggregateId to product.copy(deleted = true)),
            comments = comments,
        )
    }

    private fun ReadModel.reject(e: DomainEvent, reason: String): ReadModel =
        copy(rejected = rejected + RejectedEvent(e.eventId, reason))

    private fun ReadModel.updateSection(e: DomainEvent, f: (SectionState) -> SectionState): ReadModel {
        val s = sections[e.aggregateId] ?: return this
        if (s.deleted) return this
        return copy(sections = sections + (e.aggregateId to f(s)))
    }

    private fun ReadModel.updateProduct(e: DomainEvent, f: (ProductState) -> ProductState): ReadModel {
        val p = products[e.aggregateId] ?: return this
        if (p.deleted) return this
        return copy(products = products + (e.aggregateId to f(p)))
    }

    private fun ReadModel.updateComment(e: DomainEvent, f: (CommentState) -> CommentState): ReadModel {
        val c = comments[e.aggregateId] ?: return this
        if (c.deleted) return this
        return copy(comments = comments + (e.aggregateId to f(c)))
    }
}

private fun DomainEvent.str(key: String): String =
    payload[key] as? String ?: throw EventFormatException("Нет строки $key в ${type.wire}")

private fun DomainEvent.optLong(key: String): Long? = (payload[key] as? Number)?.toLong()

private fun DomainEvent.long(key: String): Long =
    optLong(key) ?: throw EventFormatException("Нет числа $key в ${type.wire}")

private fun DomainEvent.bool(key: String): Boolean =
    payload[key] as? Boolean ?: throw EventFormatException("Нет булева $key в ${type.wire}")
