package com.example.myapplication.data.events

import androidx.room.withTransaction
import com.example.myapplication.data.db.EventEntity
import com.example.myapplication.data.db.InventoryDatabase
import com.example.myapplication.data.db.RemoteSegmentEntity
import com.example.myapplication.data.db.SyncStateDao
import com.example.myapplication.domain.events.DomainEvent
import com.example.myapplication.domain.events.EventType
import com.example.myapplication.domain.events.Hlc
import com.example.myapplication.domain.events.EventCodec
import com.example.myapplication.domain.events.Projection
import com.example.myapplication.domain.events.ReadModel

/**
 * Алгоритм синхронизации (EVENT_SOURCING_SPEC.md, раздел 8).
 *
 * Шаги 2–3: LIST и загрузка новых удалённых сегментов.
 * Шаг 4: пересборка read model из журнала.
 * Шаг 5–6: выгрузка локальных непушнутых событий, pushed = 1 только после успешного PUT.
 *
 * Локально всё откатываемо; сеть — только в шагах чтения и PUT.
 */
class EventSyncEngine(
    private val db: InventoryDatabase,
    private val store: SegmentStore,
    private val householdId: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val eventDao = db.eventDao()
    private val remoteDao = db.remoteSegmentDao()
    private val syncState: SyncStateDao = db.syncStateDao()

    data class Report(
        val downloaded: Int,
        val applied: Int,
        val uploaded: Int,
        val rejected: Int,
    )

    /** Полный цикл синхронизации. Бросает исключения сети; локальные изменения при этом не теряются. */
    suspend fun sync(): Report {
        val downloaded = downloadRemote()
        // Пересборка read model из журнала (шаг 4). Без снапшотов — от нуля.
        val model = rebuildReadModel()
        val uploaded = uploadLocal()
        return Report(
            downloaded = downloaded.segments,
            applied = downloaded.events,
            uploaded = uploaded,
            rejected = model.rejected.size,
        )
    }

    private data class Downloaded(val segments: Int, val events: Int)

    /** Шаги 2–3. Сегменты своего устройства пропускаются: они уже в локальном журнале. */
    private suspend fun downloadRemote(): Downloaded {
        val ownDevice = syncState.get()?.deviceId ?: ""
        val prefix = "$householdId/events/"
        var segments = 0
        var events = 0
        val keys = store.list(prefix)
        for (remote in keys) {
            if (remoteDao.get(remote.key) != null) continue
            if (remote.key.startsWith("$prefix$ownDevice/")) continue
            val bytes = store.get(remote.key)
            // Валидация до записи: битый сегмент не должен попасть в журнал частично (шаг 3).
            val parsed = SegmentCodec.decode(bytes)
            db.withTransaction {
                val stamp = now()
                val known = eventDao.existingIds(parsed.map { it.eventId }).toSet()
                for (e in parsed.filter { it.eventId !in known }) {
                    eventDao.insert(e.toRemoteEntity(appliedAt = stamp))
                }
                remoteDao.upsert(RemoteSegmentEntity(key = remote.key, size = bytes.size.toLong(), appliedAt = stamp))
            }
            segments++
            events += parsed.size
        }
        return Downloaded(segments, events)
    }

    /**
     * Шаг 4: свёртка всего журнала и запись в read model — в одной транзакции.
     * Пересборка от нуля корректна при любом порядке приёма (раздел 6).
     */
    suspend fun rebuildReadModel(): ReadModel {
        val events = eventDao.all().map { it.toDomain() }
        val model = Projection.fold(events)
        db.withTransaction {
            ReadModelWriter(db).write(model, now())
        }
        return model
    }

    /**
     * Шаги 5–6. Непушнутые события группируются по устройству (здесь — только своё),
     * делятся на сегменты по лимитам и загружаются. pushed = 1 ставится только после успешного PUT.
     */
    private suspend fun uploadLocal(): Int {
        val pending = eventDao.unpushedLocal().map { it.toDomain() }
        if (pending.isEmpty()) return 0
        var uploaded = 0
        for (segment in SegmentCodec.split(pending)) {
            val first = segment.first()
            val last = segment.last()
            val key = SegmentCodec.objectKey(householdId, first.deviceId, first.seq, last.seq)
            store.putIfAbsent(key, SegmentCodec.encode(segment))
            eventDao.markPushed(segment.map { it.eventId })
            uploaded += segment.size
        }
        return uploaded
    }

    private fun EventEntity.toDomain(): DomainEvent = DomainEvent(
        eventId = eventId,
        deviceId = deviceId,
        seq = seq,
        hlc = Hlc(hlcWall, hlcCounter, deviceId),
        author = author,
        type = EventType.fromWire(type),
        aggregateId = aggregateId,
        payload = EventCodec.decodePayload(payload),
        version = version,
    )

    private fun DomainEvent.toRemoteEntity(appliedAt: Long): EventEntity = EventEntity(
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
        origin = "remote",
        pushed = true,
        appliedAt = appliedAt,
    )
}
