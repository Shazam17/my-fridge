package com.example.myapplication.domain.events

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Ошибка формата события (EVENT_SOURCING_SPEC.md, раздел 9). */
class EventFormatException(message: String) : IllegalArgumentException(message)

/** Версия новее, чем умеет читать это приложение. */
class EventVersionTooNewException(val version: Int) :
    IllegalStateException("Данные созданы более новой версией приложения (v$version).")

/**
 * Кодек JSON Lines: одна строка — одно событие (EVENT_SOURCING_SPEC.md, раздел 3).
 * Неизвестные поля payload игнорируются при чтении (вперёд-совместимость).
 */
object EventCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(event: DomainEvent): String = buildJsonObject {
        put("v", event.version)
        put("eventId", event.eventId)
        put("deviceId", event.deviceId)
        put("seq", event.seq)
        put("hlc", buildJsonObject {
            put("wall", event.hlc.wall)
            put("counter", event.hlc.counter)
        })
        put("author", event.author)
        put("type", event.type.wire)
        put("aggregate", event.type.aggregate.wire)
        put("aggregateId", event.aggregateId)
        put("payload", payloadToJson(event.payload))
    }.toString()

    fun decode(line: String): DomainEvent {
        val root = try {
            json.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            throw EventFormatException("Строка не является JSON: ${e.message}")
        }

        val version = root.int("v")
        if (version > EVENT_FORMAT_VERSION) throw EventVersionTooNewException(version)

        val hlcObj = root.obj("hlc")
        val deviceId = root.str("deviceId")
        val type = EventType.fromWire(root.str("type"))

        val aggregateWire = root.str("aggregate")
        if (aggregateWire != type.aggregate.wire) {
            throw EventFormatException("Тип $type не относится к агрегату $aggregateWire")
        }

        return DomainEvent(
            eventId = root.str("eventId"),
            deviceId = deviceId,
            seq = root.long("seq"),
            hlc = Hlc(hlcObj.long("wall"), hlcObj.int("counter"), deviceId),
            author = root.str("author"),
            type = type,
            aggregateId = root.str("aggregateId"),
            payload = jsonToPayload(root.obj("payload")),
            version = version,
        )
    }

    /** Payload одним JSON-объектом, для колонки `event.payload`. */
    fun encodePayload(payload: Map<String, Any?>): String = payloadToJson(payload).toString()

    fun decodePayload(text: String): Map<String, Any?> =
        jsonToPayload(json.parseToJsonElement(text).jsonObject)

    /** Превращает payload в JSON. Поддерживаются String, Int, Long, Boolean и null. */
    private fun payloadToJson(payload: Map<String, Any?>): JsonObject = buildJsonObject {
        payload.forEach { (key, value) ->
            when (value) {
                null -> put(key, JsonNull)
                is String -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Boolean -> put(key, value)
                else -> throw EventFormatException("Неподдерживаемый тип payload: $key=${value::class.simpleName}")
            }
        }
    }

    private fun jsonToPayload(obj: JsonObject): Map<String, Any?> =
        obj.mapValues { (_, v) -> primitiveValue(v) }

    private fun primitiveValue(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            else -> element.contentOrNull
        }
        is JsonArray, is JsonObject -> throw EventFormatException("Вложенные значения в payload не поддерживаются")
    }
}

private fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw EventFormatException("Нет строкового поля $key")

private fun JsonObject.long(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: throw EventFormatException("Нет числового поля $key")

private fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: throw EventFormatException("Нет целого поля $key")

private fun JsonObject.obj(key: String): JsonObject =
    this[key] as? JsonObject ?: throw EventFormatException("Нет объекта $key")
