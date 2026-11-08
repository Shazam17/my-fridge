package com.example.myapplication.data.sync

import com.example.myapplication.data.db.InventoryDatabase
import com.example.myapplication.data.events.EventSyncEngine
import com.example.myapplication.data.events.S3SegmentStore
import com.example.myapplication.data.s3.S3Client
import com.example.myapplication.data.s3.S3Error
import com.example.myapplication.data.s3.SyncSettingsStore
import com.example.myapplication.domain.HOUSEHOLD_ID
import com.example.myapplication.domain.events.EventFormatException
import com.example.myapplication.domain.events.EventVersionTooNewException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Точка входа кнопки «Синхронизировать» (EVENT_SOURCING_SPEC.md, раздел 8).
 * Выполняется на IO-потоке; ошибки переводятся в [SyncResult] для UI.
 */
/** Итог одной попытки синхронизации (SYNC_SPEC.md, раздел 5). */
sealed class SyncResult {
    object AlreadySynced : SyncResult()
    object Uploaded : SyncResult()
    object Merged : SyncResult()
    data class Failed(val error: S3Error) : SyncResult()
    data class Corrupted(val reason: String) : SyncResult()
    data class SchemaTooNew(val remoteVersion: Int) : SyncResult()
}

class SyncUseCase(
    private val settings: SyncSettingsStore,
    private val database: InventoryDatabase,
    private val tempDir: File,
) {
    suspend fun run(): SyncResult = withContext(Dispatchers.IO) {
        val current = settings.snapshot()
            ?: return@withContext SyncResult.Failed(S3Error.NotConfigured)
        try {
            val store = S3SegmentStore(S3Client(current), tempDir)
            val report = EventSyncEngine(database, store, householdId = HOUSEHOLD_ID).sync()
            when {
                report.downloaded > 0 -> SyncResult.Merged
                report.uploaded > 0 -> SyncResult.Uploaded
                else -> SyncResult.AlreadySynced
            }
        } catch (e: S3Error) {
            SyncResult.Failed(e)
        } catch (e: EventVersionTooNewException) {
            SyncResult.SchemaTooNew(e.version)
        } catch (e: EventFormatException) {
            SyncResult.Corrupted(e.message ?: "неверный формат")
        } catch (e: SecurityException) {
            SyncResult.Failed(S3Error.NoNetwork)
        } catch (e: IOException) {
            SyncResult.Failed(S3Error.Other(e.javaClass.simpleName))
        }
    }
}
