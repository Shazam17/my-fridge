package com.example.myapplication.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.data.db.InventoryDatabase
import com.example.myapplication.domain.ProductInput
import com.example.myapplication.data.db.ProductUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Сценарий «создать предмет в разделе»: раздел → «+» → форма → «Сохранить». */
@RunWith(AndroidJUnit4::class)
class ProductCreationTest {

    private lateinit var db: InventoryDatabase
    private lateinit var sections: SectionRepository
    private lateinit var products: ProductRepository
    private var clockValue = 1_000L

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, InventoryDatabase::class.java)
            .allowMainThreadQueries().build()
        sections = SectionRepository(db, clock = { clockValue++ })
        products = ProductRepository(db, clock = { clockValue++ })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun createdProductAppearsInChosenSection() = runBlocking {
        sections.createSection("Аптечка", "pills")
        val section = db.sectionDao().observeVisibleSections().first().first().section

        val result = products.saveProduct(
            id = null,
            sectionId = section.id,
            input = ProductInput(
                name = "Аспирин",
                quantityText = "10",
                unit = ProductUnit.PACK,
                expiresAt = LocalDate.of(2030, 1, 1),
                dosage = "500 мг",
                frequency = "2 раза в день",
            ),
        )

        assertTrue(result.errors.isEmpty())
        assertNotNull(result.value)
        val inSection = products.observeBySection(section.id).first()
        assertEquals(listOf("Аспирин"), inSection.map { it.name })
        val p = inSection.single()
        assertEquals(section.id, p.sectionId)
        assertEquals(10, p.quantity)
        assertEquals(ProductUnit.PACK, p.unit)
        assertEquals(LocalDate.of(2030, 1, 1), p.expiresAt)
        assertFalse(p.deleted)
    }

    @Test
    fun emptyNameIsRejectedAndNothingIsCreated() = runBlocking {
        sections.createSection("Еда", "food")
        val section = db.sectionDao().observeVisibleSections().first().first().section

        val result = products.saveProduct(
            id = null,
            sectionId = section.id,
            input = ProductInput("   ", "", null, null, "", ""),
        )

        assertTrue(result.errors.isNotEmpty())
        assertEquals(0, products.observeBySection(section.id).first().size)
    }
}
