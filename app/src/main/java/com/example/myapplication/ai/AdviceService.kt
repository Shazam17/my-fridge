package com.example.myapplication.ai

import com.example.myapplication.data.ProductRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Справка по предметам на локальной модели (docs/LOCAL_LLM.md).
 * Контекст — текущие предметы из базы; данные в сеть не уходят.
 */
class AdviceService(
    private val llm: LocalLlm,
    private val products: ProductRepository,
) {
    /** Ответ на вопрос пользователя с учётом его предметов. null — если модель не загружена или ошибка. */
    suspend fun ask(
        question: String,
        onText: (String) -> Unit = {},
    ): String? {
        val inventory = products.search("").first()
        val system = AdvicePrompt.build(inventory, LocalDate.now())
        return llm.generate(system = system, user = question.trim(), maxTokens = AdvicePrompt.MAX_TOKENS, onText = onText)
    }
}
