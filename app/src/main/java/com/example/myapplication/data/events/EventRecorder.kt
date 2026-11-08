package com.example.myapplication.data.events

import com.example.myapplication.data.db.EventDao
import com.example.myapplication.data.db.EventEntity
import com.example.myapplication.data.db.SyncStateDao
import com.example.myapplication.data.db.SyncStateEntity
import com.example.myapplication.domain.events.DomainEvent
import com.example.myapplication.domain.events.EventCodec
import com.example.myapplication.domain.events.EventType
import com.example.myapplication.domain.events.HlcClock
import java.util.UUID

/**
 * Записывает локальные события (EVENT_SOURCING_SPEC.md, раздел 5).
 * Вызывать ТОЛЬКО внутри транзакции Room, в которой меняется read model:
 * это инвариант «событие и изменение записываются атомарно».
 *
 * HLC и seq хранятся в sync_state; обновляются здесь же, в той же транзакции.
 */
class EventRecorder(
    private val events: EventDao,
    private val syncState: SyncStateDao,
    private val author: String,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Текущее состояние sync_state; создаётся при первом обращении. */
    private suspend fun state(): SyncStateEntity =
        syncState.get() ?: SyncStateEntity(deviceId = UUID.randomUUID().toString()).also {
            syncState.upsert(it)
        }

    /**
     * Записывает событие и возвращает его. `hlc` и `seq` назначаются здесь.
     * Поле `payload` кодируется EventCodec.
     */
    suspend fun record(type: EventType, aggregateId: String, payload: Map<String, Any?>): DomainEvent {
        val current = state()
        val clock = HlcClock(
            deviceId = current.deviceId,
            now = now,
            lastWall = current.hlcWall,
            lastCounter = current.hlcCounter,
        )
        val hlc = clock.tick()
        // seq не может повторяться (уникальный индекс deviceId+seq). Берём максимум из состояния и журнала,
        // чтобы потеря sync_state не приводила к ошибке вставки.
        val seq = maxOf(current.lastSeq, events.maxSeq(current.deviceId)) + 1
        val event = DomainEvent(
            eventId = UUID.randomUUID().toString(),
            deviceId = current.deviceId,
            seq = seq,
            hlc = hlc,
            author = author,
            type = type,
            aggregateId = aggregateId,
            payload = payload,
        )
        events.insert(event.toEntity(origin = "local", pushed = false, appliedAt = now()))
        val (wall, counter) = clock.state()
        syncState.upsert(current.copy(hlcWall = wall, hlcCounter = counter, lastSeq = seq))
        return event
    }

    private fun DomainEvent.toEntity(origin: String, pushed: Boolean, appliedAt: Long) = EventEntity(
        eventId = eventId,
        deviceId = deviceId,
        seq = seq,
        hlcWall = hlc.wall,
        hlcCounter = hlc.counter,
        author = author,
        type = type.wire,
        aggregate = type.aggregate.wire,
        aggregateId = aggregateId,
        payload = EventCodec.encodePayload(payload),
        version = version,
        origin = origin,
        pushed = pushed,
        appliedAt = appliedAt,
    )
}
