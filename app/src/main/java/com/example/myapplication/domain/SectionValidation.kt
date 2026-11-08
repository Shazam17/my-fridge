package com.example.myapplication.domain

/** Ограничения из TECHNICAL_SPEC.md, раздел 11. */
const val SECTION_NAME_MAX_LENGTH = 50

enum class SectionNameError {
    EMPTY,
    TOO_LONG,
    DUPLICATE,
}

/**
 * Проверяет название раздела.
 * Возвращает null, если название допустимо.
 * Уникальность проверяется без учёта регистра, по строкам после trim().
 */
fun validateSectionName(
    rawName: String,
    existingNames: Collection<String>,
): SectionNameError? {
    val name = rawName.trim()
    return when {
        name.isEmpty() -> SectionNameError.EMPTY
        name.length > SECTION_NAME_MAX_LENGTH -> SectionNameError.TOO_LONG
        existingNames.any { it.trim().equals(name, ignoreCase = true) } -> SectionNameError.DUPLICATE
        else -> null
    }
}
