package com.example.myapplication.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Миграция 1 → 2 без MigrationTestHelper: room-testing конфликтует с kotlinx-serialization
 * (AbstractMethodError в room-migration). DDL версии 1 берётся из экспортированной схемы `1.json`,
 * затем миграция выполняется через Room, который валидирует итоговую схему.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @Test
    fun migrate1To2_createsSyncStateTableAndKeepsData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DB_NAME)

        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DB_NAME), null)
        schemaV1Statements(context).forEach { raw.execSQL(it) }
        raw.execSQL(
            "INSERT INTO section (uuid, name, iconKey, sortOrder, isHidden, isSystem, " +
                "createdAt, updatedAt, updatedBy, deleted, deletedAt) " +
                "VALUES ('s1', 'Тест', 'other', 1, 0, 0, 1, 1, 'device', 0, NULL)",
        )
        raw.version = 1
        raw.close()

        val db = Room.databaseBuilder(context, InventoryDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        val sections = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM section").use {
            it.moveToFirst()
            it.getInt(0)
        }
        assertEquals(1, sections)
        val syncRows = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM sync_state").use {
            it.moveToFirst()
            it.getInt(0)
        }
        assertEquals(0, syncRows)
        // 2 → 3: журнал событий создан и пуст, данные сохранены
        val events = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM event").use {
            it.moveToFirst()
            it.getInt(0)
        }
        assertEquals(0, events)
        // 3 → 4: колонка isPriority добавлена (запрос падает, если колонки нет)
        db.openHelper.writableDatabase.query("SELECT isPriority FROM product LIMIT 0").use { }
        db.close()
        context.deleteDatabase(DB_NAME)
    }

    /** CREATE-выражения версии 1 из assets/1.json (сгенерированы Room). */
    private fun schemaV1Statements(context: android.content.Context): List<String> {
        val json = InstrumentationRegistry.getInstrumentation().context.assets.open("1.json").bufferedReader().use { it.readText() }
        val entities = JSONObject(json).getJSONObject("database").getJSONArray("entities")
        val result = mutableListOf<String>()
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            result += entity.getString("createSql").replace("\${TABLE_NAME}", table)
            entity.optJSONArray("indices")?.let { arr ->
                for (j in 0 until arr.length()) {
                    result += arr.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)
                }
            }
        }
        return result
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
