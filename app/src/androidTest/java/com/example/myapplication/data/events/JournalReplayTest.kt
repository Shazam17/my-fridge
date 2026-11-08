package com.example.myapplication.data.events

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.data.ProductRepository
import com.example.myapplication.data.SectionRepository
import com.example.myapplication.data.db.InventoryDatabase
import com.example.myapplication.domain.ProductInput
import com.example.myapplication.domain.events.DomainEvent
import com.example.myapplication.domain.events.EventCodec
import com.example.myapplication.domain.events.EventType
import com.example.myapplication.domain.events.Hlc
import com.example.myapplication.domain.events.Projection
import com.example.myapplication.domain.events.ReadModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Главный инвариант EVENT_SOURCING_SPEC.md: свёртка журнала воспроизводит строки Room.
 * Выполняем реальные команды репозиториев, затем сравниваем read model из журнала с таблицами.
 */
@RunWith(AndroidJUnit4::class)
class JournalReplayTest {

    private lateinit var db: InventoryDatabase
    private lateinit var sections: SectionRepository
    private lateinit var products: ProductRepository
    private var clockValue = 1_000L

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, InventoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val clock = { clockValue++ }
        sections = SectionRepository(db, clock = clock)
        products = ProductRepository(db, clock = clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun replayedJournalMatchesRows() = runBlocking {
        sections.createSection("Еда", "food")
        sections.createSection("Аптечка", "pills")
        val foodRow = allSections().first { it.name == "Еда" }
        val pillsRow = allSections().first { it.name == "Аптечка" }

        val input = ProductInput(
            name = "Молоко", quantityText = "3", unit = null,
            expiresAt = LocalDate.of(2030, 1, 15), dosage = "", frequency = "",
        )
        products.saveProduct(null, foodRow.id, input, iconKey = "milk")
        val milk = db.productDao().activeBySection(foodRow.id).first()

        products.adjustStock(milk.id, -1)
        products.setInShoppingList(milk.id, true)
        products.addComment(milk.id, "Проверить срок")
        products.saveProduct(
            milk.id, foodRow.id,
            input.copy(name = "Молоко 2,5%", quantityText = "2"),
            iconKey = "milk",
        )
        products.moveToSection(milk.id, pillsRow.id)
        products.moveToSection(milk.id, foodRow.id)

        val model = replay()
        val row = db.productDao().getById(milk.id)!!
        val sectionRow = db.sectionDao().getById(foodRow.id)!!

        val p = model.products[milk.uuid]!!
        assertEquals(row.name, p.name)
        assertEquals(row.quantity?.toLong(), p.quantity)
        assertEquals(row.isOutOfStock, p.outOfStock)
        assertEquals(row.isInShoppingList, p.inShoppingList)
        assertEquals(row.expiresAt?.toString(), p.expiresAt)
        assertEquals(row.iconKey, p.iconKey)
        assertEquals(sectionRow.uuid, p.sectionUuid)
        assertEquals(row.deleted, p.deleted)
        assertEquals(sectionRow.name, model.sections[sectionRow.uuid]!!.name)
        assertTrue(model.rejected.isEmpty())
    }

    @Test
    fun deletedSectionCascadesInJournalAndRows() = runBlocking {
        sections.createSection("Временный", "other")
        val section = allSections().first { it.name == "Временный" }
        products.saveProduct(
            null, section.id,
            ProductInput("Таблетка", "", null, null, "", ""),
        )
        val product = db.productDao().activeBySection(section.id).first()
        products.addComment(product.id, "Комментарий")

        sections.deleteSection(section.id)

        val model = replay()
        assertTrue(model.sections[section.uuid]!!.deleted)
        assertTrue(model.products[product.uuid]!!.deleted)
        assertTrue(model.comments.values.all { it.deleted })
        assertTrue(db.productDao().getById(product.id)!!.deleted)
    }

    @Test
    fun deletedProductHidesItsCommentsInJournal() = runBlocking {
        sections.createSection("Раздел", "other")
        val section = allSections().first()
        products.saveProduct(null, section.id, ProductInput("Крем", "", null, null, "", ""))
        val product = db.productDao().activeBySection(section.id).first()
        products.addComment(product.id, "Тест")

        products.deleteProduct(product.id)

        val model = replay()
        assertTrue(model.products[product.uuid]!!.deleted)
        assertTrue(model.comments.values.all { it.deleted })
        assertNull(model.rejected.firstOrNull { it.reason.contains("раздел") })
    }


    /** Разделы из таблицы напрямую: DAO отдаёт их только через Flow. */
    private fun allSections(): List<com.example.myapplication.data.db.SectionEntity> {
        val cursor = db.openHelper.readableDatabase.query("SELECT id FROM section ORDER BY id")
        val ids = cursor.use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList() }
        return ids.map { id -> kotlinx.coroutines.runBlocking { db.sectionDao().getById(id)!! } }
    }

    /** Журнал → доменные события → свёртка. */
    private suspend fun replay(): ReadModel {
        val events = db.eventDao().all().map { row ->
            DomainEvent(
                eventId = row.eventId,
                deviceId = row.deviceId,
                seq = row.seq,
                hlc = Hlc(row.hlcWall, row.hlcCounter, row.deviceId),
                author = row.author,
                type = EventType.fromWire(row.type),
                aggregateId = row.aggregateId,
                payload = EventCodec.decodePayload(row.payload),
                version = row.version,
            )
        }
        return Projection.fold(events)
    }
}
