package com.example.myapplication.domain.events

/** Текущая версия формата события (EVENT_SOURCING_SPEC.md, раздел 3). */
const val EVENT_FORMAT_VERSION = 1

/** Тип агрегата, которому принадлежит событие. */
enum class AggregateType(val wire: String) {
    SECTION("section"),
    PRODUCT("product"),
    COMMENT("comment"),
}

/**
 * Событие журнала. Неизменяемо после записи.
 * [payload] хранится как JSON-объект, поля зависят от типа (см. [EventType]).
 */
data class DomainEvent(
    val eventId: String,
    val deviceId: String,
    val seq: Long,
    val hlc: Hlc,
    val author: String,
    val type: EventType,
    val aggregateId: String,
    val payload: Map<String, Any?>,
    val version: Int = EVENT_FORMAT_VERSION,
)

/**
 * Все типы событий из EVENT_SOURCING_SPEC.md, раздел 3.1.
 * [wire] — строка в поле `type` JSON; [aggregate] — тип агрегата.
 */
enum class EventType(val wire: String, val aggregate: AggregateType) {
    SECTION_CREATED("SectionCreated", AggregateType.SECTION),
    SECTION_RENAMED("SectionRenamed", AggregateType.SECTION),
    SECTION_ICON_CHANGED("SectionIconChanged", AggregateType.SECTION),
    SECTION_HIDDEN("SectionHidden", AggregateType.SECTION),
    SECTION_ORDER_CHANGED("SectionOrderChanged", AggregateType.SECTION),
    SECTION_DELETED("SectionDeleted", AggregateType.SECTION),

    PRODUCT_CREATED("ProductCreated", AggregateType.PRODUCT),
    PRODUCT_UPDATED("ProductUpdated", AggregateType.PRODUCT),
    PRODUCT_MOVED("ProductMoved", AggregateType.PRODUCT),
    PRODUCT_QUANTITY_ADJUSTED("ProductQuantityAdjusted", AggregateType.PRODUCT),
    PRODUCT_QUANTITY_SET("ProductQuantitySet", AggregateType.PRODUCT),
    PRODUCT_OUT_OF_STOCK_SET("ProductOutOfStockSet", AggregateType.PRODUCT),
    PRODUCT_SHOPPING_LIST_SET("ProductShoppingListSet", AggregateType.PRODUCT),
    PRODUCT_PRIORITY_SET("ProductPrioritySet", AggregateType.PRODUCT),
    PRODUCT_BOUGHT("ProductBought", AggregateType.PRODUCT),
    PRODUCT_DELETED("ProductDeleted", AggregateType.PRODUCT),

    COMMENT_ADDED("CommentAdded", AggregateType.COMMENT),
    COMMENT_EDITED("CommentEdited", AggregateType.COMMENT),
    COMMENT_DELETED("CommentDeleted", AggregateType.COMMENT),
    ;

    companion object {
        /** Неизвестный тип — ошибка: читатель не может его применить. */
        fun fromWire(value: String): EventType =
            entries.firstOrNull { it.wire == value }
                ?: throw UnknownEventTypeException(value)
    }
}

class UnknownEventTypeException(type: String) : IllegalArgumentException("Неизвестный тип события: $type")
