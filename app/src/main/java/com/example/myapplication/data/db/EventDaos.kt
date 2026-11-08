package com.example.myapplication.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EventDao {

    /**
     * Явная вставка: конфликт eventId или (deviceId, seq) — ошибка, а не тихое отбрасывание.
     * Дедупликация удалённых событий выполняется до вызова (по eventId).
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT eventId FROM event WHERE eventId IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>

    /** Все события, по возрастанию HLC — вход свёртки. */
    @Query("SELECT * FROM event ORDER BY hlcWall, hlcCounter, deviceId")
    suspend fun all(): List<EventEntity>

    @Query("SELECT * FROM event WHERE origin = 'local' AND pushed = 0 ORDER BY seq")
    suspend fun unpushedLocal(): List<EventEntity>

    @Query("SELECT COUNT(*) FROM event WHERE origin = 'local' AND pushed = 0")
    suspend fun unpushedCount(): Int

    @Query("UPDATE event SET pushed = 1 WHERE eventId IN (:ids)")
    suspend fun markPushed(ids: List<String>)

    @Query("SELECT COALESCE(MAX(seq), 0) FROM event WHERE deviceId = :deviceId")
    suspend fun maxSeq(deviceId: String): Long
}

@Dao
interface RemoteSegmentDao {

    @Query("SELECT * FROM remote_segment WHERE `key` = :key")
    suspend fun get(key: String): RemoteSegmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(segment: RemoteSegmentEntity)
}
