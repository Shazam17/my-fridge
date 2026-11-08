package com.example.myapplication.domain.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class EventCodecTest {

    private fun sample(payload: Map<String, Any?>) = DomainEvent(
        eventId = "e-1",
        deviceId = "dev-A",
        seq = 42,
        hlc = Hlc(1731000000123L, 3, "dev-A"),
        author = "anna",
        type = EventType.PRODUCT_QUANTITY_ADJUSTED,
        aggregateId = "p-1",
        payload = payload,
    )

    @Test
    fun `кодирование и декодирование сохраняют событие`() {
        // JSON не различает Int и Long, поэтому числа приходят как Long: сравниваем с нормализованным payload.
        val event = sample(mapOf("delta" to -1L))
        val decoded = EventCodec.decode(EventCodec.encode(event))
        assertEquals(event, decoded)
    }

    @Test
    fun `payload поддерживает строки, числа, булевы и null`() {
        val event = sample(mapOf("name" to "Молоко", "qty" to 3, "big" to 9_000_000_000L, "ok" to true, "x" to null))
        val decoded = EventCodec.decode(EventCodec.encode(event))
        assertEquals("Молоко", decoded.payload["name"])
        assertEquals(3L, decoded.payload["qty"])
        assertEquals(true, decoded.payload["ok"])
        assertEquals(null, decoded.payload["x"])
    }

    @Test
    fun `неизвестные поля payload игнорируются`() {
        val line = """{"v":1,"eventId":"e","deviceId":"d","seq":1,"hlc":{"wall":1,"counter":0},
            "author":"a","type":"ProductQuantityAdjusted","aggregate":"product","aggregateId":"p",
            "payload":{"delta":2,"futureField":"x"}}"""
        val decoded = EventCodec.decode(line)
        assertEquals(2L, decoded.payload["delta"])
    }

    @Test
    fun `версия новее текущей отвергается`() {
        val line = EventCodec.encode(sample(mapOf("delta" to 1))).replace("\"v\":1", "\"v\":2")
        assertThrows(EventVersionTooNewException::class.java) { EventCodec.decode(line) }
    }

    @Test
    fun `неизвестный тип отвергается`() {
        val line = EventCodec.encode(sample(mapOf("delta" to 1))).replace("ProductQuantityAdjusted", "Unknown")
        assertThrows(UnknownEventTypeException::class.java) { EventCodec.decode(line) }
    }

    @Test
    fun `тип не соответствует агрегату`() {
        val line = EventCodec.encode(sample(mapOf("delta" to 1))).replace("\"aggregate\":\"product\"", "\"aggregate\":\"section\"")
        assertThrows(EventFormatException::class.java) { EventCodec.decode(line) }
    }

    @Test
    fun `строка не JSON даёт ошибку формата`() {
        assertThrows(EventFormatException::class.java) { EventCodec.decode("не json") }
    }

    @Test
    fun `закодированное событие — одна строка без переводов`() {
        val line = EventCodec.encode(sample(mapOf("name" to "a\nb")))
        assertFalse(line.contains('\n'))
    }
}
