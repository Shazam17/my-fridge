package com.example.myapplication.ai

import com.example.myapplication.data.db.ProductEntity
import com.example.myapplication.domain.ExpiryState
import com.example.myapplication.domain.expiryState
import java.time.LocalDate

/** Системный промпт для справки (docs/LOCAL_LLM.md). Без зависимости от нативной библиотеки, покрыт тестами. */
object AdvicePrompt {
    const val MAX_TOKENS = 256
    const val MAX_ITEMS_IN_PROMPT = 50

    /** Роль, ограничения и список предметов с количеством, статусом и сроком годности. */
    fun build(items: List<ProductEntity>, today: LocalDate): String = buildString {
        appendLine("Ты помощник по домашним запасам. Отвечай кратко, по-русски.")
        appendLine("Не давай медицинских советов: по лекарствам отсылай к врачу или инструкции.")
        appendLine("Если не знаешь ответа, так и скажи.")
        appendLine()
        appendLine("Предметы пользователя (сегодня $today):")
        if (items.isEmpty()) {
            appendLine("- список пуст")
        } else {
            items.take(MAX_ITEMS_IN_PROMPT).forEach { p ->
                append("- ").append(p.name)
                if (p.quantity != null) append(", количество: ").append(p.quantity)
                if (p.unit != null) append(" ").append(p.unit.label)
                if (p.isOutOfStock) append(", закончился")
                if (p.isPriority) append(", использовать в первую очередь")
                if (p.expiresAt != null) {
                    append(", срок: ").append(p.expiresAt)
                    when (expiryState(p.expiresAt, today)) {
                        ExpiryState.EXPIRED -> append(" (просрочен)")
                        ExpiryState.SOON -> append(" (скоро истекает)")
                        null -> Unit
                    }
                }
                appendLine()
            }
        }
    }
}
