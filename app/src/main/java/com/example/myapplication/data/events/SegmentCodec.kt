package com.example.myapplication.data.events

import com.example.myapplication.domain.events.DomainEvent
import com.example.myapplication.domain.events.EventCodec
import com.example.myapplication.domain.events.EventFormatException

/**
 * Сегмент — JSON Lines с событиями одного устройства (EVENT_SOURCING_SPEC.md, раздел 4.1).
 * Имя: `{householdId}/events/{deviceId}/{start:012}-{end:012}.jsonl`.
 */
object SegmentCodec {

    /** Лимиты из раздела 4.1: сегмент формируется, пока не достигнут любой из них. */
    const val MAX_EVENTS = 500
    const val MAX_BYTES = 1_000_000

    fun objectKey(householdId: String, deviceId: String, start: Long, end: Long): String =
        "$householdId/events/$deviceId/${start.pad()}-${end.pad()}.jsonl"

    /** Разбивает список непушнутых событий одного устройства (по возрастанию seq) на сегменты. */
    fun split(events: List<DomainEvent>): List<List<DomainEvent>> {
        val result = mutableListOf<List<DomainEvent>>()
        var current = mutableListOf<DomainEvent>()
        var bytes = 0
        for (e in events) {
            val size = EventCodec.encode(e).toByteArray(Charsets.UTF_8).size + 1
            if (current.isNotEmpty() && (current.size >= MAX_EVENTS || bytes + size > MAX_BYTES)) {
                result += current
                current = mutableListOf()
                bytes = 0
            }
            current += e
            bytes += size
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    /** Сериализация: одна строка на событие, завершается переводом строки. */
    fun encode(events: List<DomainEvent>): ByteArray =
        events.joinToString(separator = "") { EventCodec.encode(it) + "\n" }.toByteArray(Charsets.UTF_8)

    /**
     * Разбор и проверка сегмента (раздел 8, шаг 3): каждая строка — JSON, версия не новее поддерживаемой,
     * eventId уникален внутри сегмента. Бросает [EventFormatException] при ошибке.
     */
    fun decode(bytes: ByteArray): List<DomainEvent> {
        val text = bytes.toString(Charsets.UTF_8)
        val lines = text.split('\n').filter { it.isNotBlank() }
        val seen = HashSet<String>()
        return lines.map { line ->
            val event = EventCodec.decode(line)
            if (!seen.add(event.eventId)) throw EventFormatException("Повторный eventId ${event.eventId} в сегменте")
            event
        }
    }

    private fun Long.pad(): String = toString().padStart(12, '0')
}
