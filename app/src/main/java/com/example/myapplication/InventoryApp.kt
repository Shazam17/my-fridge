package com.example.myapplication

import android.app.Application
import android.content.Context
import com.example.myapplication.data.ProductRepository
import com.example.myapplication.data.SectionRepository
import com.example.myapplication.data.db.InventoryDatabase
import com.example.myapplication.data.s3.SyncSettingsStore

class InventoryApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Ручная DI (TECHNICAL_SPEC.md, раздел 4). */
class AppContainer(context: Context) {
    val database: InventoryDatabase = InventoryDatabase.create(context)
    val sectionRepository = SectionRepository(database)
    val productRepository = ProductRepository(database)
    val syncSettingsStore = SyncSettingsStore(context)

    /** Локальная LLM для справки (docs/LOCAL_LLM.md). Модель грузится лениво, при первом вопросе. */
    val llm = com.example.myapplication.ai.LocalLlm(context)
    val advice = com.example.myapplication.ai.AdviceService(llm, productRepository)
    val modelDownloader = com.example.myapplication.ai.ModelDownloader(context)

    val syncUseCase = com.example.myapplication.data.sync.SyncUseCase(
        settings = syncSettingsStore,
        database = database,
        tempDir = context.cacheDir.resolve("sync"),
    )
}
