package com.example.myapplication.domain

import com.example.myapplication.data.db.ProductUnit
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Ограничения из TECHNICAL_SPEC.md, раздел 11. */
const val PRODUCT_NAME_MAX_LENGTH = 100
const val DOSAGE_MAX_LENGTH = 50
const val FREQUENCY_MAX_LENGTH = 100
const val QUANTITY_MAX = 9999
const val COMMENT_MAX_LENGTH = 1000

/** FR-P11: срок истекает в ближайшие 7 дней. */
const val EXPIRY_SOON_DAYS = 7L

enum class ProductField { NAME, QUANTITY, DOSAGE, FREQUENCY }

/** Сырые значения формы. quantityText — как введено пользователем. */
data class ProductInput(
    val name: String,
    val quantityText: String,
    val unit: ProductUnit?,
    val expiresAt: LocalDate?,
    val dosage: String,
    val frequency: String,
)

/** Очищенные значения, готовые к записи. */
data class ValidProduct(
    val name: String,
    val quantity: Int?,
    val unit: ProductUnit?,
    val expiresAt: LocalDate?,
    val dosage: String?,
    val frequency: String?,
)

data class ProductValidation(
    val errors: Set<ProductField>,
    val value: ValidProduct?,
)

fun validateProduct(input: ProductInput): ProductValidation {
    val errors = mutableSetOf<ProductField>()

    val name = input.name.trim()
    if (name.isEmpty() || name.length > PRODUCT_NAME_MAX_LENGTH) errors += ProductField.NAME

    val quantityRaw = input.quantityText.trim()
    val quantity: Int? = if (quantityRaw.isEmpty()) {
        null
    } else {
        quantityRaw.toIntOrNull()?.takeIf { it in 0..QUANTITY_MAX } ?: run {
            errors += ProductField.QUANTITY
            null
        }
    }

    val dosage = input.dosage.trim().ifEmpty { null }
    if (dosage != null && dosage.length > DOSAGE_MAX_LENGTH) errors += ProductField.DOSAGE

    val frequency = input.frequency.trim().ifEmpty { null }
    if (frequency != null && frequency.length > FREQUENCY_MAX_LENGTH) errors += ProductField.FREQUENCY

    if (errors.isNotEmpty()) return ProductValidation(errors, null)

    return ProductValidation(
        errors = emptySet(),
        value = ValidProduct(
            name = name,
            quantity = quantity,
            // unit имеет смысл только с количеством; по умолчанию «шт.»
            unit = if (quantity == null) null else input.unit ?: ProductUnit.PIECE,
            expiresAt = input.expiresAt,
            dosage = dosage,
            frequency = frequency,
        ),
    )
}

/**
 * Статус «закончился» после сохранения формы.
 * При заданном количестве статус выводится из него (0 → закончился).
 * Без количества используется ручной флаг.
 */
fun outOfStockAfterSave(quantity: Int?, manualFlag: Boolean): Boolean = when (quantity) {
    null -> manualFlag
    0 -> true
    else -> false
}

data class StockChange(val quantity: Int?, val isOutOfStock: Boolean)

/**
 * Переключение «закончился» (FR-P6).
 * Пометка ставит количество 0, снятие ставит 1. Без количества меняется только флаг.
 */
fun toggleOutOfStock(quantity: Int?, isOutOfStock: Boolean): StockChange =
    if (isOutOfStock) {
        StockChange(quantity?.let { 1 }, isOutOfStock = false)
    } else {
        StockChange(quantity?.let { 0 }, isOutOfStock = true)
    }

/** Изменение количества кнопками «+» / «−» (FR-P13), в пределах 0..9999. */
fun adjustQuantity(current: Int, delta: Int): StockChange {
    val next = (current + delta).coerceIn(0, QUANTITY_MAX)
    return StockChange(next, isOutOfStock = next == 0)
}

enum class ExpiryState { EXPIRED, SOON }

/** Визуальная индикация срока годности (FR-P11). null — индикации нет. */
fun expiryState(expiresAt: LocalDate?, today: LocalDate): ExpiryState? {
    if (expiresAt == null) return null
    val days = ChronoUnit.DAYS.between(today, expiresAt)
    return when {
        days < 0 -> ExpiryState.EXPIRED
        days <= EXPIRY_SOON_DAYS -> ExpiryState.SOON
        else -> null
    }
}

fun validateComment(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.isNotEmpty() && trimmed.length <= COMMENT_MAX_LENGTH
}
