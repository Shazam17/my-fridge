package com.example.myapplication.data.events

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.data.ProductRepository
import com.example.myapplication.data.db.InventoryDatabase
import com.example.myapplication.data.db.ProductEntity
import com.example.myapplication.data.db.SectionEntity
import com.example.myapplication.domain.events.EventCodec
import com.example.myapplication.domain.events.EventType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Инвариант EVENT_SOURCING_SPEC.md, раздел 5: команда пишет событие и изменение read model
 * в одной транзакции.
 *
 * Создание предмета в журнал пока не пишется (ProductCreated не переведён), поэтому
 * сравниваем с состоянием строки только дельты: сумма delta в журнале == изменение количества.
 */
@RunWith(AndroidJUnit4::class)
class EventJournalTest {

    private lateinit var db: InventoryDatabase
    private lateinit var repo: ProductRepository
    private var productId: Long = 0
    private val initialQuantity = 5

    @Before
    fun setUp() = runBlocking {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, InventoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ProductRepository(db, clock = { 1_000L })
        val sectionId = db.sectionDao().insert(
            SectionEntity(
                uuid = "s", name = "Раздел", iconKey = "food", sortOrder = 1,
                createdAt = 1, updatedAt = 1, updatedBy = "t",
            ),
        )
        productId = db.productDao().insert(
            ProductEntity(
                uuid = "p", name = "Молоко", sectionId = sectionId, quantity = initialQuantity,
                createdAt = 1, updatedAt = 1, updatedBy = "t",
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun adjustStock_writesDeltaEventAndUpdatesRow() = runBlocking {
        repo.adjustStock(productId, -2)

        val events = db.eventDao().all()
        assertEquals(1, events.size)
        assertEquals(EventType.PRODUCT_QUANTITY_ADJUSTED.wire, events[0].type)
        assertEquals(-2L, EventCodec.decodePayload(events[0].payload)["delta"])
        assertEquals(3, db.productDao().getById(productId)!!.quantity)
    }

    @Test
    fun journalDeltas_matchRowChange() = runBlocking {
        repo.adjustStock(productId, -1)
        repo.adjustStock(productId, +4)

        val deltaSum = db.eventDao().all()
            .filter { it.type == EventType.PRODUCT_QUANTITY_ADJUSTED.wire }
            .sumOf { (EventCodec.decodePayload(it.payload)["delta"] as Long) }
        val row = db.productDao().getById(productId)!!

        assertEquals(initialQuantity + deltaSum, row.quantity!!.toLong())
    }

    @Test
    fun shoppingListChange_isJournaled() = runBlocking {
        repo.setInShoppingList(productId, true)

        val events = db.eventDao().all()
        assertEquals(listOf(EventType.PRODUCT_SHOPPING_LIST_SET.wire), events.map { it.type })
        assertTrue(db.productDao().getById(productId)!!.isInShoppingList)
    }

    @Test
    fun seqIsStrictlyIncreasingPerDevice() = runBlocking {
        repo.adjustStock(productId, -1)
        repo.adjustStock(productId, -1)
        repo.adjustStock(productId, -1)
        val seqs = db.eventDao().all().map { it.seq }.sorted()
        assertEquals(listOf(1L, 2L, 3L), seqs)
    }
}
