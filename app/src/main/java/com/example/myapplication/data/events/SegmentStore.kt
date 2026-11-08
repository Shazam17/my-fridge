package com.example.myapplication.data.events

import com.example.myapplication.data.s3.RemoteKey
import com.example.myapplication.data.s3.S3Client

/**
 * Доступ к объектам сегментов. Интерфейс отделяет синхронизацию от HTTP,
 * поэтому движок тестируется без сети.
 */
interface SegmentStore {
    /** Все ключи под `{householdId}/events/`. */
    fun list(prefix: String): List<RemoteKey>

    fun get(key: String): ByteArray

    /** true — создан; false — уже существовал и совпадает. */
    fun putIfAbsent(key: String, bytes: ByteArray): Boolean
}

/** Реализация поверх S3Client. Во временный файл пишем, потому что клиент работает с File. */
class S3SegmentStore(
    private val client: S3Client,
    private val tempDir: java.io.File,
) : SegmentStore {

    override fun list(prefix: String): List<RemoteKey> = client.listKeys(prefix)

    override fun get(key: String): ByteArray = client.getBytes(key)

    override fun putIfAbsent(key: String, bytes: ByteArray): Boolean {
        tempDir.mkdirs()
        val file = java.io.File.createTempFile("segment-", ".jsonl", tempDir)
        try {
            file.writeBytes(bytes)
            return client.putSegmentIfAbsent(key, file)
        } finally {
            file.delete()
        }
    }
}
