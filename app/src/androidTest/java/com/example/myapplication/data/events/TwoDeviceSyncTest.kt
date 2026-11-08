package com.example.myapplication.data.events

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.data.ProductRepository
import com.example.myapplication.data.SectionRepository
import com.example.myapplication.data.db.InventoryDatabase
import com.example.myapplication.data.s3.RemoteKey
import com.example.myapplication.domain.ProductInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Общее «облако» в памяти: ключ → байты. Имитирует S3 с If-None-Match. */
class InMemorySegmentStore : SegmentStore {
    val objects = sortedMapOf<String, ByteArray>()
    var puts = 0

    override fun list(prefix: String): List<RemoteKey> =
        objects.keys.filter { it.startsWith(prefix) }.map { RemoteKey(it, objects[it]!!.size.toLong()) }

    override fun get(key: String): ByteArray = objects[key] ?: error("нет $key")

    override fun putIfAbsent(key: String, bytes: ByteArray): Boolean {
        puts++
        val existing = objects[key]
        if (existing != null) {
            check(existing.contentEquals(bytes)) { "конфликт $key" }
            return false
        }
        objects[key] = bytes
        return true
    }
}

/**
 * Два устройства с собственными базами обмениваются журналами через общее хранилище.
 * Проверяет сходимость (EVENT_SOURCING_SPEC.md, раздел 11): одинаковые read model
 * и отсутствие потери правок при одновременном изменении остатка.
 */
@RunWith(AndroidJUnit4::class)
class TwoDeviceSyncTest {

    private lateinit var dbA: InventoryDatabase
    private lateinit var dbB: InventoryDatabase
    private lateinit var cloud: InMemorySegmentStore
    private var clockValue = 1_000L

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        dbA = Room.inMemoryDatabaseBuilder(context, InventoryDatabase::class.java).addCallback(com.example.myapplication.data.db.SystemSectionsSeed).allowMainThreadQueries().build()
        dbB = Room.inMemoryDatabaseBuilder(context, InventoryDatabase::class.java).addCallback(com.example.myapplication.data.db.SystemSectionsSeed).allowMainThreadQueries().build()
        cloud = InMemorySegmentStore()
    }

    @After
    fun tearDown() {
        dbA.close()
        dbB.close()
    }

    private fun sectionNamed(db: InventoryDatabase, name: String) =
        allSectionsOf(db).first { it.name == name }

    private fun allSectionsOf(db: InventoryDatabase): List<com.example.myapplication.data.db.SectionEntity> {
        val ids = db.openHelper.readableDatabase.query("SELECT id FROM section").use { c ->
            generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList()
        }
        return ids.map { id -> kotlinx.coroutines.runBlocking { db.sectionDao().getById(id)!! } }
    }

    private fun engine(db: InventoryDatabase) =
        EventSyncEngine(db, cloud, householdId = "fam", now = { clockValue })

    private fun products(db: InventoryDatabase) =
        ProductRepository(db, clock = { clockValue++ })

    private fun sections(db: InventoryDatabase) =
        SectionRepository(db, clock = { clockValue++ })

    @Test
    fun concurrentAdjustmentsAreSummed() = runBlocking {
        // Устройство A создаёт раздел и предмет (остаток 10), синхронизирует.
        sections(dbA).createSection("Еда", "food")
        val sectionA = sectionNamed(dbA, "Еда")
        products(dbA).saveProduct(null, sectionA.id, ProductInput("Молоко", "10", null, null, "", ""))
        val milkA = dbA.productDao().activeBySection(sectionA.id).first()
        engine(dbA).sync()

        // Устройство B получает данные и синхронизирует их.
        engine(dbB).sync()
        val milkB = dbB.productDao().getByUuid(milkA.uuid)!!

        // Одновременно: A −1, B −1, без синхронизации между ними.
        products(dbA).adjustStock(milkA.id, -1)
        products(dbB).adjustStock(milkB.id, -1)

        engine(dbA).sync()
        engine(dbB).sync()
        engine(dbA).sync()

        // Итог: 10 − 1 − 1 = 8. Дельты складываются, правка не теряется (раздел 1).
        assertEquals(8, dbA.productDao().getByUuid(milkA.uuid)!!.quantity)
        assertEquals(8, dbB.productDao().getByUuid(milkA.uuid)!!.quantity)
    }

    @Test
    fun productInSystemSectionReachesOtherDevice() = runBlocking {
        // Системный раздел «Продукты» есть на обоих устройствах без событий.
        val foodA = dbA.sectionDao().getByUuid("section-food")!!
        products(dbA).saveProduct(null, foodA.id, ProductInput("Хлеб", "2", null, null, "", ""))
        engine(dbA).sync()

        engine(dbB).sync()
        val hleb = dbB.productDao().activeBySection(dbB.sectionDao().getByUuid("section-food")!!.id)
        assertEquals(listOf("Хлеб"), hleb.map { it.name })
    }

    @Test
    fun devicesConvergeToSameReadModel() = runBlocking {
        sections(dbA).createSection("Аптечка", "pills")
        val s = sectionNamed(dbA, "Аптечка")
        products(dbA).saveProduct(null, s.id, ProductInput("Аспирин", "", null, LocalDate.of(2030, 1, 1), "", ""))
        val p = dbA.productDao().activeBySection(s.id).first()
        products(dbA).addComment(p.id, "Проверить")

        engine(dbA).sync()
        engine(dbB).sync()

        val a = engine(dbA).rebuildReadModel()
        val b = engine(dbB).rebuildReadModel()
        assertEquals(a.products.keys, b.products.keys)
        assertEquals(a.products.values.map { it.name }, b.products.values.map { it.name })
        assertEquals(a.comments.values.map { it.text }, b.comments.values.map { it.text })
        assertTrue(dbB.commentDao().activeByProduct(dbB.productDao().getByUuid(p.uuid)!!.id).isNotEmpty())
    }

    @Test
    fun uploadedSegmentIsNotReuploadedAndPushedFlagIsSet() = runBlocking {
        sections(dbA).createSection("Еда", "food")
        engine(dbA).sync()
        val putsAfterFirst = cloud.puts
        assertEquals(0, dbA.eventDao().unpushedCount())

        engine(dbA).sync()
        assertEquals(putsAfterFirst, cloud.puts)
    }

    @Test
    fun ownSegmentIsNotDownloadedAgain() = runBlocking {
        sections(dbA).createSection("Еда", "food")
        engine(dbA).sync()
        val report = engine(dbA).sync()
        assertEquals(0, report.downloaded)
    }
}
