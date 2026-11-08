package com.example.myapplication.domain.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ProjectionTest {

    private var seq = 0L

    private fun ev(
        type: EventType,
        aggregateId: String,
        wall: Long,
        device: String = "A",
        payload: Map<String, Any?> = emptyMap(),
    ): DomainEvent = DomainEvent(
        eventId = "$device-${++seq}",
        deviceId = device,
        seq = seq,
        hlc = Hlc(wall, 0, device),
        author = device,
        type = type,
        aggregateId = aggregateId,
        payload = payload,
    )

    private fun sectionCreated(id: String, wall: Long = 1) = ev(
        EventType.SECTION_CREATED, id, wall,
        payload = mapOf("name" to "Холодильник", "iconKey" to "fridge", "sortOrder" to 0L),
    )

    private fun productCreated(id: String, section: String, wall: Long = 2, qty: Long? = 5) = ev(
        EventType.PRODUCT_CREATED, id, wall,
        payload = mapOf("name" to "Молоко", "sectionId" to section, "quantity" to qty),
    )

    @Test
    fun `одновременное уменьшение остатка на двух устройствах не теряет дельты`() {
        val base = listOf(sectionCreated("s"), productCreated("p", "s", qty = 5))
        val a = ev(EventType.PRODUCT_QUANTITY_ADJUSTED, "p", 10, device = "A", payload = mapOf("delta" to -1))
        val b = ev(EventType.PRODUCT_QUANTITY_ADJUSTED, "p", 10, device = "B", payload = mapOf("delta" to -1))
        val model = Projection.fold(base + a + b)
        assertEquals(3L, model.products["p"]!!.quantity)
    }

    @Test
    fun `остаток не уходит ниже нуля и ставит закончился`() {
        val events = listOf(
            sectionCreated("s"),
            productCreated("p", "s", qty = 1),
            ev(EventType.PRODUCT_QUANTITY_ADJUSTED, "p", 5, payload = mapOf("delta" to -3)),
        )
        val p = Projection.fold(events).products["p"]!!
        assertEquals(0L, p.quantity)
        assertTrue(p.outOfStock)
    }

    @Test
    fun `результат не зависит от порядка приёма событий`() {
        val events = listOf(
            sectionCreated("s"),
            productCreated("p", "s", qty = 5),
            ev(EventType.PRODUCT_QUANTITY_ADJUSTED, "p", 10, device = "A", payload = mapOf("delta" to -1)),
            ev(EventType.PRODUCT_QUANTITY_ADJUSTED, "p", 10, device = "B", payload = mapOf("delta" to 2)),
            ev(EventType.PRODUCT_QUANTITY_SET, "p", 20, device = "C", payload = mapOf("quantity" to 9L)),
            ev(EventType.PRODUCT_SHOPPING_LIST_SET, "p", 30, device = "A", payload = mapOf("inList" to true)),
        )
        val expected = Projection.fold(events)
        val rnd = Random(42)
        repeat(50) {
            assertEquals(expected, Projection.fold(events.shuffled(rnd)))
        }
    }

    @Test
    fun `повторно пришедшее событие не применяется дважды`() {
        val s = sectionCreated("s")
        val p = productCreated("p", "s", qty = 5)
        val adj = ev(EventType.PRODUCT_QUANTITY_ADJUSTED, "p", 10, payload = mapOf("delta" to -1))
        val once = Projection.fold(listOf(s, p, adj))
        val twice = Projection.fold(listOf(s, p, adj, adj))
        assertEquals(once, twice)
        assertEquals(4L, twice.products["p"]!!.quantity)
    }

    @Test
    fun `удаление раздела каскадно помечает предметы и комментарии`() {
        val events = listOf(
            sectionCreated("s"),
            productCreated("p", "s"),
            ev(EventType.COMMENT_ADDED, "c", 3, payload = mapOf("productId" to "p", "text" to "нет")),
            ev(EventType.SECTION_DELETED, "s", 5),
        )
        val model = Projection.fold(events)
        assertTrue(model.sections["s"]!!.deleted)
        assertTrue(model.products["p"]!!.deleted)
        assertTrue(model.comments["c"]!!.deleted)
    }

    @Test
    fun `перенос предмета в удалённый раздел отклоняется`() {
        val events = listOf(
            sectionCreated("s1"),
            sectionCreated("s2", wall = 1),
            productCreated("p", "s1"),
            ev(EventType.SECTION_DELETED, "s2", 5),
            ev(EventType.PRODUCT_MOVED, "p", 6, payload = mapOf("sectionId" to "s2")),
        )
        val model = Projection.fold(events)
        assertEquals("s1", model.products["p"]!!.sectionUuid)
        assertEquals(1, model.rejected.size)
    }

    @Test
    fun `комментарий к удалённому предмету отклоняется`() {
        val events = listOf(
            sectionCreated("s"),
            productCreated("p", "s"),
            ev(EventType.PRODUCT_DELETED, "p", 4),
            ev(EventType.COMMENT_ADDED, "c", 5, payload = mapOf("productId" to "p", "text" to "x")),
        )
        val model = Projection.fold(events)
        assertTrue(model.comments.isEmpty())
        assertEquals(1, model.rejected.size)
    }

    @Test
    fun `покупка возвращает ноль в единицу и снимает закончился`() {
        val events = listOf(
            sectionCreated("s"),
            productCreated("p", "s", qty = 0),
            ev(EventType.PRODUCT_SHOPPING_LIST_SET, "p", 3, payload = mapOf("inList" to true)),
            ev(EventType.PRODUCT_BOUGHT, "p", 4),
        )
        val p = Projection.fold(events).products["p"]!!
        assertEquals(1L, p.quantity)
        assertTrue(!p.outOfStock)
        assertTrue(!p.inShoppingList)
    }
}
