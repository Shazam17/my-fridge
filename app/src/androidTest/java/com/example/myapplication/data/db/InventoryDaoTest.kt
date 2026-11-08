package com.example.myapplication.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class InventoryDaoTest {

    private lateinit var db: InventoryDatabase

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, InventoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun product(
        sectionId: Long,
        name: String,
        uuid: String = name,
        isOutOfStock: Boolean = false,
        inList: Boolean = false,
        deleted: Boolean = false,
        expiresAt: LocalDate? = null,
    ) = ProductEntity(
        uuid = uuid,
        name = name,
        sectionId = sectionId,
        isOutOfStock = isOutOfStock,
        isInShoppingList = inList,
        deleted = deleted,
        deletedAt = if (deleted) 1L else null,
        expiresAt = expiresAt,
        createdAt = 1L,
        updatedAt = 1L,
        updatedBy = "device",
    )

    /** Системный раздел «Продукты» создаётся onCreate callback'ом только в файловой БД, поэтому заводим свой. */
    private suspend fun insertSection(name: String = "Раздел", uuid: String = name, hidden: Boolean = false): Long =
        db.sectionDao().insert(
            SectionEntity(
                uuid = uuid,
                name = name,
                iconKey = "other",
                sortOrder = 1,
                isHidden = hidden,
                createdAt = 1L,
                updatedAt = 1L,
                updatedBy = "device",
            ),
        )

    @Test
    fun observeBySection_putsAvailableFirstAndSortsByName() = runBlocking {
        val s = insertSection()
        db.productDao().insert(product(s, "Яблоко", uuid = "a"))
        db.productDao().insert(product(s, "Банан", uuid = "b", isOutOfStock = true))
        db.productDao().insert(product(s, "Абрикос", uuid = "c"))

        val names = db.productDao().observeBySection(s).first().map { it.name }
        assertEquals(listOf("Абрикос", "Яблоко", "Банан"), names)
    }

    @Test
    fun observeBySection_hidesDeletedProducts() = runBlocking {
        val s = insertSection()
        db.productDao().insert(product(s, "Живой", uuid = "a"))
        db.productDao().insert(product(s, "Удалённый", uuid = "b", deleted = true))

        val names = db.productDao().observeBySection(s).first().map { it.name }
        assertEquals(listOf("Живой"), names)
    }

    @Test
    fun visibleSections_countOnlyActiveProductsAndSkipHiddenAndDeleted() = runBlocking {
        val visible = insertSection("Видимый", uuid = "v")
        val hidden = insertSection("Скрытый", uuid = "h", hidden = true)
        db.productDao().insert(product(visible, "Один", uuid = "p1"))
        db.productDao().insert(product(visible, "Два удалён", uuid = "p2", deleted = true))
        db.productDao().insert(product(hidden, "Скрытый предмет", uuid = "p3"))

        val result = db.sectionDao().observeVisibleSections().first()
        assertEquals(listOf("Видимый"), result.map { it.section.name })
        assertEquals(1, result.single().productCount)
    }

    @Test
    fun shoppingList_containsOnlyFlaggedActiveProducts() = runBlocking {
        val s = insertSection()
        db.productDao().insert(product(s, "В списке", uuid = "a", inList = true))
        db.productDao().insert(product(s, "Не в списке", uuid = "b"))
        db.productDao().insert(product(s, "Удалён в списке", uuid = "c", inList = true, deleted = true))

        val names = db.productDao().observeShoppingList().first().map { it.name }
        assertEquals(listOf("В списке"), names)
    }

    @Test
    fun search_matchesSubstringAcrossSections() = runBlocking {
        val a = insertSection("A", uuid = "sa")
        val b = insertSection("B", uuid = "sb")
        db.productDao().insert(product(a, "Молоко", uuid = "p1"))
        db.productDao().insert(product(b, "Молочный шоколад", uuid = "p2"))
        db.productDao().insert(product(b, "Хлеб", uuid = "p3"))

        val names = db.productDao().search("молоч").first().map { it.name }
        assertEquals(listOf("Молочный шоколад"), names)
    }

    @Test
    fun comments_areNewestFirstAndHideDeleted() = runBlocking {
        val s = insertSection()
        val pid = db.productDao().insert(product(s, "Товар", uuid = "p"))
        db.commentDao().insert(CommentEntity(uuid = "c1", productId = pid, text = "старый", createdAt = 1L, updatedAt = 1L, updatedBy = "device"))
        db.commentDao().insert(CommentEntity(uuid = "c2", productId = pid, text = "новый", createdAt = 2L, updatedAt = 2L, updatedBy = "device"))
        db.commentDao().insert(CommentEntity(uuid = "c3", productId = pid, text = "удалён", createdAt = 3L, updatedAt = 3L, updatedBy = "device", deleted = true))

        val texts = db.commentDao().observeByProduct(pid).first().map { it.text }
        assertEquals(listOf("новый", "старый"), texts)
    }

    @Test
    fun deleteSection_cascadesToProductsAndComments() = runBlocking {
        val s = insertSection()
        val pid = db.productDao().insert(product(s, "Товар", uuid = "p"))
        db.commentDao().insert(CommentEntity(uuid = "c", productId = pid, text = "x", createdAt = 1L, updatedAt = 1L, updatedBy = "device"))

        // Физическое удаление раздела каскадно удаляет предметы и комментарии (FK CASCADE).
        db.openHelper.writableDatabase.execSQL("DELETE FROM section WHERE id = $s")

        assertNull(db.productDao().getById(pid))
        assertTrue(db.commentDao().activeByProduct(pid).isEmpty())
    }

    @Test
    fun syncState_upsertReplacesSingleRow() = runBlocking {
        db.syncStateDao().upsert(SyncStateEntity(deviceId = "dev-1"))
        db.syncStateDao().upsert(SyncStateEntity(remoteEtag = "etag", lastSyncAt = 5L, deviceId = "dev-1"))

        val state = db.syncStateDao().get()
        assertNotNull(state)
        assertEquals("etag", state!!.remoteEtag)
        assertEquals(5L, state.lastSyncAt)
        assertFalse(state.id != 1)
    }
}
