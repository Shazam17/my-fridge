package com.example.myapplication.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Локальный журнал событий (EVENT_SOURCING_SPEC.md, раздел 5).
 * Источник истины для синхронизации. Не редактируется после записи.
 * Таблица локальная: в S3 уходят только строки с pushed = 0 (раздел 4).
 */
@Entity(
    tableName = "event",
    indices = [
        Index(value = ["deviceId", "seq"], unique = true),
        Index(value = ["pushed"]),
    ],
)
data class EventEntity(
    @PrimaryKey val eventId: String,
    val deviceId: String,
    val seq: Long,
    val hlcWall: Long,
    val hlcCounter: Int,
    val author: String,
    val type: String,
    val aggregate: String,
    val aggregateId: String,
    /** JSON-объект payload (EventCodec). */
    val payload: String,
    val version: Int,
    /** local | remote */
    val origin: String,
    /** 0 — ещё не отправлено в S3, 1 — отправлено или получено. */
    val pushed: Boolean,
    val appliedAt: Long,
)

/** Учёт скачанных сегментов, чтобы не скачивать их повторно (раздел 4.3). */
@Entity(tableName = "remote_segment")
data class RemoteSegmentEntity(
    @PrimaryKey val key: String,
    val size: Long,
    val appliedAt: Long,
)
