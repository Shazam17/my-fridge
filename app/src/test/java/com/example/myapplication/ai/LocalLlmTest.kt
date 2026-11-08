package com.example.myapplication.ai

import com.example.myapplication.data.db.ProductEntity
import com.example.myapplication.data.db.ProductUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LocalLlmTest {

    @Test
    fun completeUtf8KeepsWholeCharacters() {
        val full = "Ё".toByteArray(Charsets.UTF_8) // 2 байта
        assertEquals(2, Utf8Boundary.completeLength(full))
        // Первый байт двухбайтового символа без второго: ждём продолжения.
        assertEquals(0, Utf8Boundary.completeLength(full.copyOf(1)))
    }

    @Test
    fun completeUtf8HandlesFourByteEmoji() {
        val emoji = "\uD83C\uDF4E".toByteArray(Charsets.UTF_8) // 🍎, 4 байта
        assertEquals(4, Utf8Boundary.completeLength(emoji))
        assertEquals(0, Utf8Boundary.completeLength(emoji.copyOf(3)))
    }

    @Test
    fun asciiIsAlwaysComplete() {
        assertEquals(3, Utf8Boundary.completeLength("abc".toByteArray()))
    }

    @Test
    fun systemPromptContainsItemsAndFlags() {
        val items = listOf(
            ProductEntity(
                id = 1, uuid = "u1", sectionId = 1, name = "Молоко",
                quantity = 2, unit = ProductUnit.LITER,
                expiresAt = LocalDate.of(2026, 5, 11),
                isPriority = true,
                createdAt = 0L, updatedAt = 0L, updatedBy = "test",
            ),
        )
        val prompt = AdvicePrompt.build(items, LocalDate.of(2026, 5, 10))
        assertTrue(prompt.contains("Молоко, количество: 2 л"))
        assertTrue(prompt.contains("использовать в первую очередь"))
        assertTrue(prompt.contains("скоро истекает"))
    }

    @Test
    fun emptyInventoryIsStated() {
        val prompt = AdvicePrompt.build(emptyList(), LocalDate.of(2026, 5, 10))
        assertTrue(prompt.contains("список пуст"))
    }
}
